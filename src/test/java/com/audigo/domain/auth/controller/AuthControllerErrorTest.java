package com.audigo.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.service.AuthService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthControllerErrorTest {

    @Test
    void missingRefreshCookieIsUnauthorized() {
        AuthCookieManager cookies = mock(AuthCookieManager.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookies.readRefreshToken(request)).thenReturn(Optional.empty());
        AuthController controller = new AuthController(mock(AuthService.class), cookies);

        assertThatThrownBy(() -> controller.refresh(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
    }
}
