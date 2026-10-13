package com.audigo.domain.chat.config;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = "chatUserId";

    private final AuthCookieManager authCookieManager;
    private final TokenService tokenService;

    public ChatWebSocketHandshakeInterceptor(AuthCookieManager authCookieManager, TokenService tokenService) {
        this.authCookieManager = authCookieManager;
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Optional<String> token = authCookieManager.readAccessToken(httpRequest)
                .or(() -> readBearerToken(request.getHeaders()));
        Optional<Long> userId = token.flatMap(tokenService::findUserIdByAccessToken);
        if (userId.isEmpty()) {
            return false;
        }
        attributes.put(USER_ID_ATTRIBUTE, userId.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }

    private Optional<String> readBearerToken(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authorization.substring(7));
    }
}
