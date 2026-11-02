package com.audigo.global.security;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final AuthCookieManager authCookieManager;
    private final SecurityContextRepository securityContextRepository;

    public BearerTokenAuthenticationFilter(
            TokenService tokenService,
            AuthCookieManager authCookieManager,
            SecurityContextRepository securityContextRepository
    ) {
        this.tokenService = tokenService;
        this.authCookieManager = authCookieManager;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        resolveAccessToken(request).flatMap(tokenService::findUserIdByAccessToken).ifPresent(userId -> {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
        });
        filterChain.doFilter(request, response);
    }

    private Optional<String> resolveAccessToken(HttpServletRequest request) {
        return authCookieManager.readAccessToken(request).or(() -> {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                return Optional.of(authorization.substring(7));
            }
            return Optional.empty();
        });
    }
}
