package com.audigo.global.migration.pending;

import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

// 인터셉터·AsyncListener에서 예외 핸들러를 거치지 않고 ApiResponse 형식으로 응답한다
@Component
public class MigrationErrorResponseWriter {

    static final String RETRY_AFTER_SECONDS = "5";

    private final ObjectMapper objectMapper;

    public MigrationErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeRetryLater(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(errorCode.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(ApiResponse.of(errorCode.message(), null)));
        response.flushBuffer();
    }
}
