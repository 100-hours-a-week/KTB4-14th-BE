package com.audigo.global.migration.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// /internal/** 전용 체인에서만 쓴다. 전역 서블릿 필터로 등록되지 않도록 빈으로 만들지 않는다.
// 키 값은 로그·응답 어디에도 남기지 않는다
public class AdminKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Migration-Admin-Key";
    static final String ROLE = "MIGRATION_ADMIN";

    private static final Logger log = LoggerFactory.getLogger(AdminKeyAuthenticationFilter.class);
    private static final String UNAUTHORIZED_BODY = "{\"message\":\"migration_admin_unauthorized\",\"data\":null}";

    // 길이가 달라도 비교 시간이 같도록 해시끼리 비교한다. 키가 비어 있으면 null
    private final byte[] expectedKeyHash;

    public AdminKeyAuthenticationFilter(String adminKey) {
        this.expectedKeyHash = adminKey == null || adminKey.isBlank() ? null : sha256(adminKey);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 키가 없는 환경에서는 API가 없는 것처럼 보이게 한다
        if (expectedKeyHash == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expectedKeyHash, sha256(provided))) {
            log.warn("migration_admin_auth_failed remote_ip={} method={} uri={} reason={}",
                    request.getRemoteAddr(), request.getMethod(), request.getRequestURI(),
                    provided == null ? "missing_key" : "invalid_key");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "migration-admin", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
