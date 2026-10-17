package com.audigo.global.config;

import com.audigo.global.security.BearerTokenAuthenticationFilter;
import com.audigo.global.logging.RequestLoggingFilter;
import com.audigo.global.migration.MigrationProperties;
import com.audigo.global.migration.admin.AdminKeyAuthenticationFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    // 컷오버 관리 API 전용 체인. 관리자 키로만 접근하며 사용자 JWT로는 접근할 수 없다
    @Bean
    @Order(1)
    SecurityFilterChain migrationAdminSecurityFilterChain(
            HttpSecurity http,
            MigrationProperties migrationProperties
    ) throws Exception {
        AdminKeyAuthenticationFilter adminKeyFilter = new AdminKeyAuthenticationFilter(migrationProperties.adminKey());
        return http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MIGRATION_ADMIN"))
                .addFilterBefore(adminKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestLoggingFilter(), AdminKeyAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            CorsProperties corsProperties
    ) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsProperties)))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/health", "/users/login", "/users/refresh").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RequestLoggingFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.resolvedAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Authorization", RequestLoggingFilter.REQUEST_ID_HEADER));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
