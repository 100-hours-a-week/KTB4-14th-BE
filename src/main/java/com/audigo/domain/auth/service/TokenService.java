package com.audigo.domain.auth.service;

import com.audigo.domain.auth.dto.AuthTokens;
import com.audigo.domain.auth.token.JwtTokenProvider;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final RedisRefreshSessionStore refreshSessionStore;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public TokenService(
            @Value("${audigo.auth.access-token-ttl-seconds}") long accessTokenTtlSeconds,
            @Value("${audigo.auth.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds,
            RedisRefreshSessionStore refreshSessionStore,
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.refreshSessionStore = refreshSessionStore;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthTokens issue(Long userId) {
        findActiveUser(userId);
        for (int attempt = 0; attempt < 3; attempt++) {
            RefreshSessionToken token = RefreshSessionToken.create();
            if (refreshSessionStore.create(token, userId, null, refreshTtl())) {
                return tokens(userId, token);
            }
        }
        throw new IllegalStateException("Failed to allocate a refresh session ID.");
    }

    public AuthTokens refresh(String refreshToken) {
        RefreshSessionToken current = RefreshSessionToken.parse(refreshToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        RefreshSessionData session = refreshSessionStore.find(current.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (!session.secretHash().equals(current.secretHash())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        findActiveUser(session.userId());

        RefreshSessionToken next = current.rotate();
        AuthTokens tokens = tokens(session.userId(), next);
        if (!refreshSessionStore.rotate(current, next, refreshTtl())) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return tokens;
    }

    public void revoke(String refreshToken) {
        RefreshSessionToken.parse(refreshToken).ifPresent(refreshSessionStore::revoke);
    }

    public Optional<Long> findUserIdByAccessToken(String accessToken) {
        return jwtTokenProvider.parseAccessToken(accessToken);
    }

    private AuthTokens tokens(Long userId, RefreshSessionToken token) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, accessTokenTtlSeconds);
        return new AuthTokens(accessToken, token.value(), accessTokenTtlSeconds, refreshTokenTtlSeconds);
    }

    private Duration refreshTtl() {
        return Duration.ofSeconds(refreshTokenTtlSeconds);
    }

    private User findActiveUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }
        return user;
    }
}
