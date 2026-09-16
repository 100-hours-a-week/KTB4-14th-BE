package com.audigo.domain.auth.oauth;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class KakaoOAuthClientImpl implements KakaoOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClientImpl.class);
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final KakaoOAuthProperties properties;
    private final RestClient restClient;

    public KakaoOAuthClientImpl(KakaoOAuthProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public KakaoOAuthUserInfo fetchUserInfo(String authorizationCode) {
        if (properties.clientId() == null || properties.clientId().isBlank()) {
            throw new BusinessException(ErrorCode.KAKAO_OAUTH_FAILED);
        }

        try {
            KakaoTokenResponse token = exchangeCode(authorizationCode);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                log.warn("Kakao token exchange succeeded but access_token was empty.");
                throw new BusinessException(ErrorCode.KAKAO_OAUTH_FAILED);
            }
            KakaoUserResponse user = requestUserInfo(token.accessToken());
            if (user == null || user.id() == null) {
                log.warn("Kakao user info response did not contain user id.");
                throw new BusinessException(ErrorCode.KAKAO_OAUTH_FAILED);
            }
            return user.toUserInfo();
        } catch (RestClientResponseException exception) {
            log.warn(
                    "Kakao OAuth request failed. status={}, response={}",
                    exception.getStatusCode(),
                    exception.getResponseBodyAsString()
            );
            throw new BusinessException(ErrorCode.KAKAO_OAUTH_FAILED);
        } catch (RestClientException exception) {
            log.warn("Kakao OAuth request failed before receiving a response.", exception);
            throw new BusinessException(ErrorCode.KAKAO_OAUTH_FAILED);
        }
    }

    private KakaoTokenResponse exchangeCode(String authorizationCode) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", properties.clientId());
        body.add("redirect_uri", properties.redirectUri());
        body.add("code", authorizationCode);
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            body.add("client_secret", properties.clientSecret());
        }

        return restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(KakaoTokenResponse.class);
    }

    private KakaoUserResponse requestUserInfo(String accessToken) {
        return restClient.get()
                .uri(USER_INFO_URL)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(KakaoUserResponse.class);
    }

    private record KakaoTokenResponse(
            @JsonProperty("access_token") String accessToken
    ) {
    }

    private record KakaoUserResponse(
            Long id,
            @JsonProperty("kakao_account") KakaoAccount kakaoAccount
    ) {
        KakaoOAuthUserInfo toUserInfo() {
            KakaoProfile profile = kakaoAccount == null ? null : kakaoAccount.profile();
            String nickname = profile == null || profile.nickname() == null || profile.nickname().isBlank()
                    ? "카카오 여행자"
                    : profile.nickname();
            String profileImageUrl = profile == null ? null : profile.profileImageUrl();
            return new KakaoOAuthUserInfo(Objects.toString(id), nickname, profileImageUrl);
        }
    }

    private record KakaoAccount(
            KakaoProfile profile
    ) {
    }

    private record KakaoProfile(
            String nickname,
            @JsonProperty("profile_image_url") String profileImageUrl
    ) {
    }
}
