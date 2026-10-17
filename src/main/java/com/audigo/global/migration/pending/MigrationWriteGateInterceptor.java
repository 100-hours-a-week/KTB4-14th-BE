package com.audigo.global.migration.pending;

import com.audigo.global.migration.classify.RequestKind;
import com.audigo.global.migration.classify.WriteRequestClassifier;
import com.audigo.global.migration.gate.Admission;
import com.audigo.global.migration.gate.MigrationWriteGate;
import com.audigo.global.migration.tracking.HttpWorkTickets;
import com.audigo.global.migration.tracking.WorkType;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

// 인증·인가를 통과한 요청을 컨트롤러 진입 전(트랜잭션·커넥션 확보 전)에 분류하고 입장을 판정한다.
// Admitted → 티켓을 요청에 묶고 실행, Hold → 보류 큐에 넣고 요청 스레드 반납
@Component
public class MigrationWriteGateInterceptor implements AsyncHandlerInterceptor {

    // 재개(06)할 때 보류 항목(PendingWrite)을 이 속성에 담아 ASYNC 디스패치한다
    public static final String RELEASED_ATTR = MigrationWriteGateInterceptor.class.getName() + ".released";

    private final WriteRequestClassifier classifier;
    private final MigrationWriteGate gate;
    private final PendingWriteQueue queue;

    public MigrationWriteGateInterceptor(
            WriteRequestClassifier classifier,
            MigrationWriteGate gate,
            PendingWriteQueue queue
    ) {
        this.classifier = classifier;
        this.gate = gate;
        this.queue = queue;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return preHandleAsyncDispatch(request);
        }
        if (classifier.classify(request, handler) != RequestKind.WRITE) {
            return true;
        }
        return switch (gate.tryAdmit(WorkType.HTTP_WRITE, describe(request))) {
            case Admission.Admitted admitted -> {
                HttpWorkTickets.bind(request, admitted.ticket());
                yield true;
            }
            case Admission.Hold hold -> {
                queue.hold(request, response);
                yield false;
            }
            case Admission.Skip skip -> throw new IllegalStateException("HTTP 쓰기 요청은 Skip 판정을 받지 않는다");
        };
    }

    // 재개된 요청은 큐를 거치지 않고 한 번만 입장한다.
    // 그 외 ASYNC 디스패치(비동기 컨트롤러의 결과 처리)는 최초 디스패치에서 이미 판정·티켓 발급을 마쳤다
    private boolean preHandleAsyncDispatch(HttpServletRequest request) {
        if (!(request.getAttribute(RELEASED_ATTR) instanceof PendingWrite pending)) {
            return true;
        }
        if (!pending.markReleased()) {
            throw new IllegalStateException("재개 요청이 이미 실행됐거나 재개 대상이 아니다: " + pending);
        }
        request.removeAttribute(RELEASED_ATTR);
        HttpWorkTickets.bind(request, gate.admitReleased(describe(request)).ticket());
        return true;
    }

    // 컨트롤러·서비스의 커밋 또는 롤백이 끝난 뒤 호출된다 (OSIV off)
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        HttpWorkTickets.closeBound(request);
    }

    // 비동기 컨트롤러는 응답이 끝날 때 티켓을 닫는다
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpWorkTickets.closeOnAsyncCompletion(request);
    }

    private static String describe(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
