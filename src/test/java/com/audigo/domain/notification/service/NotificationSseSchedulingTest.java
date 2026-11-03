package com.audigo.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.audigo.domain.notification.config.NotificationSseConfig;
import com.audigo.domain.notification.config.NotificationSseProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseSchedulingTest {

    @Test
    void configured_scheduler_writes_heartbeats_and_context_shutdown_completes_sse() throws Exception {
        MvcResult result;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sse-test", Map.of(
                    "audigo.notification.sse.timeout", "2s",
                    "audigo.notification.sse.heartbeat-interval", "20ms")));
            context.register(Config.class);
            context.refresh();
            Controller controller = new Controller(context.getBean(NotificationSseService.class));
            result = MockMvcBuilders.standaloneSetup(controller).build().perform(get("/test/stream"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertThat(controller.emitter.getTimeout()).isEqualTo(2_000L);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(result.getResponse().getContentAsString())
                            .contains("event:connected", "event:resync_required", ":heartbeat"));
        }
        assertThat(result.getAsyncResult(1_000)).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(NotificationSseProperties.class)
    @Import({NotificationSseConfig.class, NotificationSseService.class})
    static class Config {
    }

    @RestController
    static class Controller {
        private final NotificationSseService service;
        private SseEmitter emitter;

        Controller(NotificationSseService service) {
            this.service = service;
        }

        @GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe() {
            emitter = service.subscribe(7L);
            return emitter;
        }
    }
}
