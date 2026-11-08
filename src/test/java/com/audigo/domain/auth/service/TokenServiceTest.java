package com.audigo.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.auth.dto.AuthTokens;
import com.audigo.domain.auth.token.JwtTokenProvider;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private RedisRefreshSessionStore store;
    private UserRepository users;
    private JwtTokenProvider jwt;
    private TokenService service;

    @BeforeEach
    void setUp() {
        store = mock(RedisRefreshSessionStore.class);
        users = mock(UserRepository.class);
        jwt = mock(JwtTokenProvider.class);
        service = new TokenService(3600, 1209600, store, users, jwt);
    }

    @Test
    void loginIssuesRedisBackedRefreshToken() {
        activeUser(42L);
        when(store.create(any(), eq(42L), eq(Duration.ofDays(14)))).thenReturn(true);
        when(jwt.createAccessToken(42L, 3600)).thenReturn("access");

        AuthTokens tokens = service.issue(42L);

        assertThat(RefreshSessionToken.parse(tokens.refreshToken())).isPresent();
        assertThat(tokens.accessToken()).isEqualTo("access");
        verify(store).create(any(), eq(42L), eq(Duration.ofDays(14)));
    }

    @Test
    void refreshRotatesCurrentToken() {
        RefreshSessionToken current = RefreshSessionToken.create();
        activeUser(42L);
        when(store.find(current.sessionId()))
                .thenReturn(Optional.of(new RefreshSessionData(42L, current.secretHash())));
        when(store.rotate(eq(current), any(), eq(Duration.ofDays(14)))).thenReturn(true);
        when(jwt.createAccessToken(42L, 3600)).thenReturn("access");

        AuthTokens tokens = service.refresh(current.value());

        RefreshSessionToken next = RefreshSessionToken.parse(tokens.refreshToken()).orElseThrow();
        assertThat(next.sessionId()).isEqualTo(current.sessionId());
        assertThat(next.secret()).isNotEqualTo(current.secret());
    }

    @Test
    void legacyTokenIsRejectedWithoutRedisLookup() {
        assertThatThrownBy(() -> service.refresh("AUDIGO_REFRESH_legacy"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(store, users);
    }

    @Test
    void losingRotationRaceRejectsRequest() {
        RefreshSessionToken current = RefreshSessionToken.create();
        activeUser(42L);
        when(store.find(current.sessionId()))
                .thenReturn(Optional.of(new RefreshSessionData(42L, current.secretHash())));
        when(store.rotate(eq(current), any(), eq(Duration.ofDays(14)))).thenReturn(false);

        assertThatThrownBy(() -> service.refresh(current.value()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    private void activeUser(Long userId) {
        User user = mock(User.class);
        when(user.isActive()).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }
}
