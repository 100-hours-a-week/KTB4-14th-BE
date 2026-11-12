package com.audigo.domain.chat.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Recording is opt-in, with separate lab and server target validation. */
@Component
public class ChatMessageSearchOutboxProperties {
    private final boolean recordEnabled;
    private final boolean workerEnabled;
    private final String targetGeneration;
    private final String targetIndex;
    private final int requestTimeoutSeconds;
    private final int leaseSeconds;
    private final int retryBaseSeconds;
    private final int retryMaxSeconds;
    private final int maxPerCycle;

    public ChatMessageSearchOutboxProperties(Environment environment) {
        recordEnabled = environment.getProperty("audigo.chat.search-sync.record-enabled", Boolean.class, false);
        workerEnabled = environment.getProperty("audigo.chat.search-sync.worker-enabled", Boolean.class, false);
        targetGeneration = environment.getProperty("audigo.chat.search-sync.target-generation", "");
        targetIndex = environment.getProperty("audigo.chat.search-sync.target-index", "");
        requestTimeoutSeconds = environment.getProperty("audigo.chat.search-sync.request-timeout-seconds", Integer.class, 3);
        leaseSeconds = environment.getProperty("audigo.chat.search-sync.lease-seconds", Integer.class, 30);
        retryBaseSeconds = environment.getProperty("audigo.chat.search-sync.retry-base-seconds", Integer.class, 1);
        retryMaxSeconds = environment.getProperty("audigo.chat.search-sync.retry-max-seconds", Integer.class, 60);
        maxPerCycle = environment.getProperty("audigo.chat.search-sync.max-per-cycle", Integer.class, 10);
        boolean server = ChatMessageSearchDeployment.isServer(environment);
        if (!recordEnabled && !workerEnabled) return;
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 10 || leaseSeconds < requestTimeoutSeconds * 3 ||
                leaseSeconds > 300 || retryBaseSeconds < 1 || retryMaxSeconds < retryBaseSeconds ||
                retryMaxSeconds > 3600 || maxPerCycle < 1 || maxPerCycle > 100) {
            throw new IllegalArgumentException("Invalid search sync timeout, lease, retry or batch settings");
        }
        if (server) {
            ChatMessageSearchDeployment.validateServerTarget(environment);
            if (workerEnabled) {
                ChatMessageSearchDeployment.base(environment, true);
                ChatMessageSearchDeployment.authorization(environment, true);
            }
            return;
        }
        if (!environment.acceptsProfiles(Profiles.of("search-sync-lab"))) {
            throw new IllegalArgumentException("Search outbox recording requires search-sync-lab");
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/audigo_search_sync_lab(?:\\?.*)?")) {
            throw new IllegalArgumentException("Search outbox recording requires the isolated local sync database");
        }
        if (!targetGeneration.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("A search target generation is required");
        }
        if (!targetIndex.matches("chat-messages-sync-v1-\\d{8}-\\d{6}-\\d{3}")) {
            throw new IllegalArgumentException("Search outbox recording requires a separate sync index");
        }
    }

    public boolean recordEnabled() { return recordEnabled; }
    public boolean workerEnabled() { return workerEnabled; }
    public String targetGeneration() { return targetGeneration; }
    public String targetIndex() { return targetIndex; }
    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int leaseSeconds() { return leaseSeconds; }
    public int retryBaseSeconds() { return retryBaseSeconds; }
    public int retryMaxSeconds() { return retryMaxSeconds; }
    public int maxPerCycle() { return maxPerCycle; }
}
