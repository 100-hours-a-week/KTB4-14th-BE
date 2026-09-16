package com.audigo.domain.auth.controller;

import com.audigo.domain.auth.dto.KakaoLoginRequest;
import com.audigo.domain.auth.dto.LoginResponse;
import com.audigo.domain.auth.dto.LogoutRequest;
import com.audigo.domain.auth.dto.RefreshTokenRequest;
import com.audigo.domain.auth.dto.TokenResponse;
import com.audigo.domain.auth.service.AuthService;
import com.audigo.global.response.ApiResponse;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> loginWithKakao(@Valid @RequestBody KakaoLoginRequest request) {
        return ApiResponse.of("login_success", authService.loginWithKakao(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.of("token_refreshed", authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
