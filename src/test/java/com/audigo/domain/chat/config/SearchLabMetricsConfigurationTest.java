package com.audigo.domain.chat.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SearchLabMetricsConfigurationTest {
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfiguration {
        @Bean SecurityFilterChain fallback(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).build();
        }
        @Bean Probe probe() { return new Probe(); }
    }
    @RestController
    static class Probe {
        @GetMapping({"/actuator/prometheus", "/api/probe"}) String get() { return "probe"; }
    }

    @Test
    void exposes_only_the_management_metrics_path_with_both_lab_profiles() throws Exception {
        try (var context = context("search-lab", "search-lab-metrics")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(18081); return request; })).andExpect(status().isOk());
            mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(18080); return request; })).andExpect(status().isForbidden());
            mvc.perform(get("/api/probe").with(request -> { request.setLocalPort(18081); return request; })).andExpect(status().isForbidden());
        }
    }

    @Test
    void normal_profiles_do_not_open_the_metrics_path() throws Exception {
        for (String[] profiles : new String[][]{{"search-lab"}, {"search-lab-metrics"}, {"production"}}) {
            try (var context = context(profiles)) {
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(18081); return request; })).andExpect(status().isForbidden());
            }
        }
    }

    private AnnotationConfigWebApplicationContext context(String... profiles) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profiles);
        context.register(TestConfiguration.class, SearchLabMetricsConfiguration.class);
        context.refresh();
        return context;
    }
}
