package com.audigo.domain.chat.config;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Alternate engines require an isolated lab or an explicitly configured server deployment. */
@Component
public class ChatMessageSearchMode {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageSearchMode.class);
    private final String mode;
    private final boolean lab;
    private final String esIndex;
    private final String esTextContract;

    public ChatMessageSearchMode(Environment environment) {
        mode = environment.getProperty("audigo.chat.message-search-mode", "fulltext")
                .trim().toLowerCase(Locale.ROOT);
        boolean syncLab = environment.acceptsProfiles(Profiles.of("search-sync-lab"));
        lab = environment.acceptsProfiles(Profiles.of("search-lab")) || syncLab;
        boolean server = ChatMessageSearchDeployment.isServer(environment);
        if (!mode.equals("fulltext") && !mode.equals("instr") && !mode.equals("es")) {
            throw new IllegalArgumentException("audigo.chat.message-search-mode must be fulltext, instr or es");
        }
        if (!mode.equals("fulltext") && !lab && !server) {
            throw new IllegalArgumentException("alternate search modes require a search lab or search-server profile");
        }
        esIndex = environment.getProperty("audigo.chat.es.index", "");
        esTextContract = environment.getProperty("audigo.chat.es.text-contract", "baseline");
        if (!esTextContract.equals("baseline") && !esTextContract.equals("nfc-v1")) {
            throw new IllegalArgumentException("ES text contract must be baseline or nfc-v1");
        }
        String prefix = esTextContract.equals("nfc-v1") ? "chat-messages-nfc-v1-" : "chat-messages-poc-v1-";
        if (mode.equals("es") && syncLab) {
            new ChatMessageSearchOutboxProperties(environment);
            if (!esTextContract.equals("nfc-v1") ||
                    !environment.getProperty("spring.datasource.url", "").matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/audigo_search_sync_lab(?:\\?.*)?") ||
                    !esIndex.equals(environment.getProperty("audigo.chat.search-sync.target-index", ""))) {
                throw new IllegalArgumentException("Sync search requires NFC, the isolated database and the same write target");
            }
            prefix = "chat-messages-sync-v1-";
        }
        if (mode.equals("es") && server) {
            ChatMessageSearchDeployment.validateServerTarget(environment);
            ChatMessageSearchDeployment.base(environment, false);
            ChatMessageSearchDeployment.authorization(environment, false);
        }
        if (mode.equals("es") && !server && !esIndex.matches(prefix + "\\d{8}-\\d{6}-\\d{3}")) {
            throw new IllegalArgumentException("es mode requires a verified physical lab index");
        }
        log.info("chat_message_search_mode mode={} search_lab={} search_server={}", mode, lab, server);
    }

    public boolean usesFulltext() { return mode.equals("fulltext"); }
    public boolean usesEs() { return mode.equals("es"); }
    public String esIndex() { return esIndex; }
    public boolean isLab() { return lab; }
    public String value() { return mode; }
    public boolean usesNfc() { return usesEs() && esTextContract.equals("nfc-v1"); }
    public String esTextContract() { return esTextContract; }
}
