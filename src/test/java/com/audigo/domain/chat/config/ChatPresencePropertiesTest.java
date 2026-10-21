package com.audigo.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ChatPresencePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsDefaultDurations() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            ChatPresenceProperties properties = context.getBean(ChatPresenceProperties.class);
            assertThat(properties.renewalInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.keyTtl()).isEqualTo(Duration.ofSeconds(90));
        });
    }

    @Test
    void allowsDurationOverrides() {
        contextRunner
                .withPropertyValues(
                        "audigo.chat.presence.renewal-interval=10s",
                        "audigo.chat.presence.lease-duration=30s",
                        "audigo.chat.presence.key-ttl=60s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ChatPresenceProperties properties = context.getBean(ChatPresenceProperties.class);
                    assertThat(properties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.keyTtl()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void rejectsNonPositiveDurations() {
        contextRunner
                .withPropertyValues("audigo.chat.presence.renewal-interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRenewalIntervalThatIsNotShorterThanLease() {
        contextRunner
                .withPropertyValues(
                        "audigo.chat.presence.renewal-interval=45s",
                        "audigo.chat.presence.lease-duration=45s"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsKeyTtlThatIsNotLongerThanLease() {
        contextRunner
                .withPropertyValues(
                        "audigo.chat.presence.lease-duration=45s",
                        "audigo.chat.presence.key-ttl=45s"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ChatPresenceProperties.class)
    static class TestConfiguration {
    }
}
