package com.audigo.domain.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Separate, loopback-only listener for the isolated mixed-performance environment. */
@Configuration(proxyBeanMethods = false)
@Profile("search-sync-lab & search-sync-perf-metrics")
public class SearchSyncPerfMetricsConfiguration {
    public SearchSyncPerfMetricsConfiguration(Environment environment) {
        if (!environment.getProperty("server.port", "").equals("18086") ||
                !environment.getProperty("management.server.port", "").equals("18087") ||
                !environment.getProperty("management.server.address", "").equals("127.0.0.1") ||
                !environment.getProperty("spring.datasource.url", "").matches("jdbc:mysql://127\\.0\\.0\\.1:13308/audigo_search_sync_lab(?:\\?.*)?")) {
            throw new IllegalArgumentException("Perf diagnostics require the dedicated loopback API, management listener and database");
        }
    }

    @Bean
    @Order(0)
    SecurityFilterChain searchSyncPerfMetricsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> request.getLocalPort() == 18087 && request.getRequestURI().equals("/actuator/prometheus"))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
