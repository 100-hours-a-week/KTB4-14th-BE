package com.audigo.global.migration.admin;

import com.audigo.global.error.ErrorCode;
import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.gate.PhaseCommand;
import com.audigo.global.migration.gate.TransitionFailure;
import com.audigo.global.migration.gate.TransitionResult;
import com.audigo.global.migration.pending.PendingWriteDispatcher;
import com.audigo.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 인스턴스 단위 컷오버 제어 API. ALB를 거치지 않고 인스턴스 사설 IP로 호출한다.
// /internal/** 전용 보안 체인(관리자 키)으로 보호되며, classifier가 BYPASS로 판정해 보류 단계에서도 동작한다.
// 모든 명령은 Gate 규칙대로 멱등이다. 같은 명령을 다시 보내면 현재 상태를 반환한다
@RestController
@RequestMapping("/internal/migration-control")
public class MigrationControlController {

    private final MigrationWriteGate gate;
    private final PendingWriteDispatcher dispatcher;
    private final MigrationStatusAssembler statusAssembler;
    private final MigrationProperties properties;

    public MigrationControlController(
            MigrationWriteGate gate,
            PendingWriteDispatcher dispatcher,
            MigrationStatusAssembler statusAssembler,
            MigrationProperties properties
    ) {
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.statusAssembler = statusAssembler;
        this.properties = properties;
    }

    // AI 시작 지연 ON. 컷오버 시도의 첫 명령이며 이후 같은 migrationId·epoch로 drain한다
    @PostMapping("/defer-ai-start")
    public ResponseEntity<ApiResponse<MigrationCommandResult>> deferAiStart(
            @Valid @RequestBody MigrationCommandRequest request
    ) {
        return respond(gate.deferAiStart(command(request)), HttpStatus.ACCEPTED);
    }

    // drain 전에 컷오버를 포기할 때 AI 시작 지연만 해제한다
    @PostMapping("/cancel-ai-start-deferral")
    public ResponseEntity<ApiResponse<MigrationCommandResult>> cancelAiStartDeferral(
            @Valid @RequestBody MigrationCommandRequest request
    ) {
        return respond(gate.cancelAiStartDeferral(command(request)), HttpStatus.OK);
    }

    @PostMapping("/drain")
    public ResponseEntity<ApiResponse<MigrationCommandResult>> drain(
            @Valid @RequestBody MigrationCommandRequest request
    ) {
        return respond(gate.drain(command(request)), HttpStatus.ACCEPTED);
    }

    // 잔여 작업이 있으면 wait_seconds(최대 drain-timeout)까지 기다린 뒤 다시 시도한다
    @PostMapping("/freeze")
    public ResponseEntity<ApiResponse<MigrationCommandResult>> freeze(
            @Valid @RequestBody MigrationCommandRequest request
    ) throws InterruptedException {
        PhaseCommand command = command(request);
        TransitionResult result = gate.freeze(command);
        Duration wait = waitDuration(request);
        if (result.failure() == TransitionFailure.REMAINING_WORK && !wait.isZero()) {
            gate.awaitDrained(wait);
            result = gate.freeze(command);
        }
        return respond(result, HttpStatus.OK);
    }

    // 보류 요청을 제한된 동시성으로 재개하고, 큐가 비면 OPEN으로 돌아간다
    @PostMapping("/resume")
    public ResponseEntity<ApiResponse<MigrationCommandResult>> resume(
            @Valid @RequestBody MigrationCommandRequest request
    ) {
        if (request.target() == null) {
            ErrorCode code = ErrorCode.MIGRATION_RESUME_TARGET_REQUIRED;
            return ResponseEntity.status(code.status()).body(ApiResponse.of(code.message(), null));
        }
        return respond(dispatcher.resume(command(request), request.target()), HttpStatus.ACCEPTED);
    }

    @GetMapping("/status")
    public ApiResponse<MigrationStatusResponse> status() {
        return ApiResponse.of("migration_status_found", statusAssembler.assemble());
    }

    private ResponseEntity<ApiResponse<MigrationCommandResult>> respond(TransitionResult result, HttpStatus success) {
        MigrationCommandResult body = new MigrationCommandResult(
                result.outcome().name(),
                result.failure() == null ? null : result.failure().name(),
                statusAssembler.assemble(result.snapshot()),
                statusAssembler.tickets(result.remainingWork()),
                result.remainingTransactions().stream()
                        .map(transaction -> new MigrationCommandResult.RemainingTransaction(
                                transaction.name(), transaction.threadName(), transaction.startedAt().toString()))
                        .toList()
        );
        if (result.isSuccess()) {
            String message = result.outcome() == TransitionResult.Outcome.APPLIED
                    ? "migration_command_applied"
                    : "migration_command_idempotent";
            return ResponseEntity.status(success).body(ApiResponse.of(message, body));
        }
        ErrorCode code = errorCode(result.failure());
        return ResponseEntity.status(code.status()).body(ApiResponse.of(code.message(), body));
    }

    private Duration waitDuration(MigrationCommandRequest request) {
        if (request.waitSeconds() == null || request.waitSeconds() <= 0) {
            return Duration.ZERO;
        }
        Duration requested = Duration.ofSeconds(request.waitSeconds());
        return requested.compareTo(properties.drainTimeout()) > 0 ? properties.drainTimeout() : requested;
    }

    private static PhaseCommand command(MigrationCommandRequest request) {
        return new PhaseCommand(request.migrationId(), request.epoch());
    }

    private static ErrorCode errorCode(TransitionFailure failure) {
        return switch (failure) {
            case MIGRATION_DISABLED -> ErrorCode.MIGRATION_DISABLED;
            case MIGRATION_ID_MISMATCH -> ErrorCode.MIGRATION_ID_MISMATCH;
            case STALE_COMMAND -> ErrorCode.MIGRATION_STALE_COMMAND;
            case ATTEMPT_IN_PROGRESS -> ErrorCode.MIGRATION_ATTEMPT_IN_PROGRESS;
            case INVALID_TRANSITION -> ErrorCode.MIGRATION_INVALID_PHASE;
            case REMAINING_WORK -> ErrorCode.MIGRATION_REMAINING_WORK;
        };
    }
}
