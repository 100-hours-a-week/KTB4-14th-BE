package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class SearchSyncPerfMetricsConfigurationTest {
    static final Map<String, String> PROPERTIES = Map.of("server.port", "18086", "management.server.port", "18087",
            "management.server.address", "127.0.0.1", "spring.datasource.url", "jdbc:mysql://127.0.0.1:13308/audigo_search_sync_lab?connectionTimeZone=UTC");

    @Test void only_the_dedicated_management_metrics_path_is_public() throws Exception {
        try (var context = context("search-sync-lab", "search-sync-perf-metrics")) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/actuator/prometheus").with(r -> { r.setLocalPort(18087); return r; })).andExpect(status().isOk());
            mvc.perform(get("/actuator/prometheus").with(r -> { r.setLocalPort(18086); return r; })).andExpect(status().isForbidden());
            mvc.perform(get("/api/probe").with(r -> { r.setLocalPort(18087); return r; })).andExpect(status().isForbidden());
        }
    }

    @Test void existing_and_normal_profiles_do_not_open_perf_metrics() throws Exception {
        for (String[] profiles : new String[][]{{"search-sync-lab"}, {"search-sync-perf-metrics"}, {"production"}, {"search-lab", "search-lab-metrics"}}) {
            try (var context = context(profiles)) {
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                mvc.perform(get("/actuator/prometheus").with(r -> { r.setLocalPort(18087); return r; })).andExpect(status().isForbidden());
            }
        }
    }

    @Test void refuses_public_listener_or_existing_sync_database() {
        for (var invalid : Map.of("management.server.address", "0.0.0.0", "management.server.port", "18081", "server.port", "18084",
                "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/audigo_search_sync_lab").entrySet()) {
            var environment = new MockEnvironment(); PROPERTIES.forEach(environment::setProperty);
            environment.setProperty(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> new SearchSyncPerfMetricsConfiguration(environment)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private AnnotationConfigWebApplicationContext context(String... profiles) {
        var context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("perf-test", new HashMap<>(PROPERTIES)));
        context.register(SearchLabMetricsConfigurationTest.TestConfiguration.class, SearchSyncPerfMetricsConfiguration.class);
        context.refresh(); return context;
    }
}
