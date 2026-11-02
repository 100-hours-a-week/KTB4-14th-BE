package com.audigo.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.audigo.domain.auth.cookie.AuthCookieManager;
import com.audigo.domain.auth.service.TokenService;
import com.audigo.global.security.BearerTokenAuthenticationFilter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(classes = SseSecurityLifecycleTest.TestApplication.class,
        properties = "spring.config.name=sse-security-test")
class SseSecurityLifecycleTest {

    @Autowired private WebApplicationContext context;
    @Autowired private TokenService tokenService;
    @Autowired private SseController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(tokenService.findUserIdByAccessToken("valid-token")).thenReturn(Optional.of(7L));
    }

    @Test
    void authenticated_sse_can_complete_after_the_initial_security_context_has_been_cleared() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/notifications/subscribe")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getResponse().isCommitted()).isTrue();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(controller.userId).isEqualTo(7L);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        controller.emitter.complete();

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void anonymous_and_invalid_token_requests_cannot_open_sse() throws Exception {
        mockMvc.perform(get("/api/notifications/subscribe")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications/subscribe").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authentication_is_not_reused_by_a_new_request_without_a_token() throws Exception {
        MvcResult authenticated = mockMvc.perform(get("/api/notifications/subscribe")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(request().asyncStarted()).andReturn();
        controller.emitter.complete();
        mockMvc.perform(asyncDispatch(authenticated)).andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/subscribe")).andExpect(status().isForbidden());
    }

    @RestController
    static class SseController {
        private SseEmitter emitter;
        private Long userId;

        @GetMapping(value = "/api/notifications/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe() throws IOException {
            userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            emitter = new SseEmitter(30_000L);
            emitter.send(SseEmitter.event().name("connected").data("ready"));
            return emitter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, BearerTokenAuthenticationFilter.class, SseController.class})
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
