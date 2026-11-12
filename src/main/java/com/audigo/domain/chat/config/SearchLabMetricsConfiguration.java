package com.audigo.domain.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Opt-in local diagnostic endpoint, on the loopback-only management listener. */
@Configuration(proxyBeanMethods = false)
@Profile("search-lab & search-lab-metrics")
public class SearchLabMetricsConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain searchLabMetricsSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> request.getLocalPort() == 18081
                        && request.getRequestURI().equals("/actuator/prometheus"))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
