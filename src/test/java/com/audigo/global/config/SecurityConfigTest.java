package com.audigo.global.config;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.service.TokenService;
import com.audigo.global.security.BearerTokenAuthenticationFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
        classes = SecurityConfigTest.TestApplication.class,
        properties = {
                "spring.config.name=security-test",
                "management.endpoints.web.exposure.include=health,prometheus"
        }
)
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void monitoring_ip_can_scrape_real_prometheus_metrics_without_authentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(request -> {
                    request.setRemoteAddr("10.0.10.230");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void another_ip_cannot_scrape_even_with_a_forwarded_monitoring_ip() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Forwarded-For", "10.0.10.230")
                        .with(request -> {
                            request.setRemoteAddr("10.0.10.231");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void an_authenticated_user_from_another_ip_cannot_scrape() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(user("user"))
                        .with(request -> {
                            request.setRemoteAddr("10.0.10.231");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void health_remains_accessible_without_authentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void monitoring_ip_still_requires_authentication_for_other_api_paths() throws Exception {
        mockMvc.perform(get("/users/me").with(request -> {
                    request.setRemoteAddr("10.0.10.230");
                    return request;
                }))
                .andExpect(status().isForbidden());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, BearerTokenAuthenticationFilter.class})
    static class TestApplication {

        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("http://localhost:5173"));
        }

        @Bean
        TokenService tokenService() {
            return mock(TokenService.class);
        }

        @Bean
        AuthCookieManager authCookieManager() {
            return mock(AuthCookieManager.class);
        }
    }
}
