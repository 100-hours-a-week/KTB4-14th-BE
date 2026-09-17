package com.audigo.domain.auth.service;

import com.audigo.domain.auth.dto.AuthTokens;
import com.audigo.domain.auth.entity.RefreshToken;
import com.audigo.domain.auth.token.JwtTokenProvider;
import com.audigo.domain.auth.repository.RefreshTokenRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {

    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public TokenService(
            @Value("${audigo.auth.access-token-ttl-seconds}") long accessTokenTtlSeconds,
            @Value("${audigo.auth.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds,
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public AuthTokens issue(Long userId) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, accessTokenTtlSeconds);
        String refreshToken = "AUDIGO_REFRESH_" + UUID.randomUUID();
        Instant now = Instant.now();

        User user = findActiveUser(userId);
        refreshTokenRepository.save(RefreshToken.create(
                user,
                TokenHash.sha256(refreshToken),
                LocalDateTime.ofInstant(now.plusSeconds(refreshTokenTtlSeconds), ZoneId.systemDefault())
        ));

        return new AuthTokens(accessToken, refreshToken, accessTokenTtlSeconds, refreshTokenTtlSeconds);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(TokenHash.sha256(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        if (!storedToken.isUsable()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        storedToken.revoke();
        return issue(storedToken.user().id());
    }

    @Transactional
    public void revoke(String refreshToken) {
        refreshTokenRepository.findByTokenHash(TokenHash.sha256(refreshToken))
                .ifPresent(RefreshToken::revoke);
    }

    public Optional<Long> findUserIdByAccessToken(String accessToken) {
        return jwtTokenProvider.parseAccessToken(accessToken);
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
