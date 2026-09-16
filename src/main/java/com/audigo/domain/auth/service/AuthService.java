package com.audigo.domain.auth.service;

import com.audigo.domain.auth.dto.AuthUserResponse;
import com.audigo.domain.auth.dto.KakaoLoginRequest;
import com.audigo.domain.auth.dto.LoginResponse;
import com.audigo.domain.auth.dto.TokenResponse;
import com.audigo.domain.auth.entity.OAuthAccount;
import com.audigo.domain.auth.entity.OAuthProvider;
import com.audigo.domain.auth.oauth.KakaoOAuthClient;
import com.audigo.domain.auth.oauth.KakaoOAuthUserInfo;
import com.audigo.domain.auth.repository.OAuthAccountRepository;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    public AuthService(
            KakaoOAuthClient kakaoOAuthClient,
            OAuthAccountRepository oauthAccountRepository,
            UserRepository userRepository,
            TokenService tokenService
    ) {
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.oauthAccountRepository = oauthAccountRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    @Transactional
    public LoginResponse loginWithKakao(KakaoLoginRequest request) {
        if (!OAuthProvider.KAKAO.name().equalsIgnoreCase(request.provider())) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_PROVIDER);
        }

        KakaoOAuthUserInfo oauthUser = kakaoOAuthClient.fetchUserInfo(request.authorizationCode());
        UserLoginResult result = findOrCreateUser(oauthUser);
        TokenResponse tokens = tokenService.issue(result.user().id());

        return new LoginResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.tokenType(),
                tokens.accessTokenExpiresIn(),
                tokens.refreshTokenExpiresIn(),
                AuthUserResponse.of(result.user(), result.isNewUser())
        );
    }

    public TokenResponse refresh(String refreshToken) {
        return tokenService.refresh(refreshToken);
    }

    public void logout(String refreshToken) {
        tokenService.revoke(refreshToken);
    }

    private UserLoginResult findOrCreateUser(KakaoOAuthUserInfo oauthUser) {
        return oauthAccountRepository
                .findByProviderAndProviderUserId(OAuthProvider.KAKAO, oauthUser.providerUserId())
                .map(account -> {
                    User user = account.user();
                    user.updateProfileImageUrl(oauthUser.profileImageUrl());
                    return new UserLoginResult(user, false);
                })
                .orElseGet(() -> {
                    User user = userRepository.save(User.create(oauthUser.nickname(), oauthUser.profileImageUrl()));
                    oauthAccountRepository.save(OAuthAccount.create(
                            OAuthProvider.KAKAO,
                            oauthUser.providerUserId(),
                            user
                    ));
                    return new UserLoginResult(user, true);
                });
    }

    private record UserLoginResult(
            User user,
            boolean isNewUser
    ) {
    }
}
