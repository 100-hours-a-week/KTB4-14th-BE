package com.audigo.domain.auth.controller;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.dto.AuthLoginResult;
import com.audigo.domain.auth.dto.AuthTokens;
import com.audigo.domain.auth.dto.AuthUserResponse;
import com.audigo.domain.auth.dto.KakaoLoginRequest;
import com.audigo.domain.auth.service.AuthService;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieManager authCookieManager;

    public AuthController(AuthService authService, AuthCookieManager authCookieManager) {
        this.authService = authService;
        this.authCookieManager = authCookieManager;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthUserResponse>> loginWithKakao(
            @Valid @RequestBody KakaoLoginRequest request
    ) {
        AuthLoginResult result = authService.loginWithKakao(request);
        return ResponseEntity.ok()
                .headers(authCookieManager.issue(result.tokens()))
                .body(ApiResponse.of("login_success", result.response()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Void>> refresh(HttpServletRequest request) {
        String refreshToken = authCookieManager.readRefreshToken(request)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_REQUIRED));
        AuthTokens tokens = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .headers(authCookieManager.issue(tokens))
                .body(ApiResponse.of("token_refreshed", null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authCookieManager.readRefreshToken(request).ifPresent(authService::logout);
        return ResponseEntity.noContent()
                .headers(authCookieManager.clear())
                .build();
    }
}
