package com.audigo.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.auth.service.AuthSessionUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthSessionErrorResponseTest {

    @Test
    void redisFailureReturnsServiceUnavailableWithoutInternalCause() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/users/refresh");

        var response = new GlobalExceptionHandler().handleAuthSessionUnavailable(
                new AuthSessionUnavailableException(new IllegalStateException("internal address")),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().message()).isEqualTo("auth_service_unavailable");
    }
}
