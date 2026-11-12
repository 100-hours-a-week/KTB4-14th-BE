package com.audigo.domain.chat.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Explicit server opt-in; local experiments retain their original isolation checks. */
public final class ChatMessageSearchDeployment {
    private static final String LOCAL_URL = "http://127.0.0.1:19200";
    private static final String LOCAL_PASSWORD = "audigo-search-lab-es-only-password";

    private ChatMessageSearchDeployment() {}

    public static boolean isServer(Environment environment) {
        boolean server = environment.acceptsProfiles(Profiles.of("search-server"));
        if (server && environment.acceptsProfiles(Profiles.of("search-lab", "search-sync-lab"))) {
            throw new IllegalArgumentException("search-server cannot be combined with search lab profiles");
        }
        return server;
    }

    public static void validateServerTarget(Environment environment) {
        String expectedDatabase = required(environment, "audigo.chat.search-sync.database-name");
        if (!expectedDatabase.matches("[a-zA-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid search sync database name");
        }
        String jdbcUrl = required(environment, "spring.datasource.url");
        try {
            if (!jdbcUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI database = URI.create(jdbcUrl.substring(5));
            if (database.getHost() == null || database.getRawUserInfo() != null ||
                    database.getRawFragment() != null || !database.getRawPath().equals("/" + expectedDatabase)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Search sync database does not match the configured MySQL database");
        }
        String index = required(environment, "audigo.chat.search-sync.target-index");
        // The Outbox column is VARCHAR(100). Only one concrete index name, no wildcard or multi-target syntax.
        if (!index.matches("[a-z0-9][a-z0-9._-]{0,99}") ||
                !index.equals(required(environment, "audigo.chat.es.index"))) {
            throw new IllegalArgumentException("Search and sync require the same concrete ES index name (max 100 characters)");
        }
        if (!required(environment, "audigo.chat.search-sync.target-generation")
                .matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("A valid search target generation is required");
        }
        if (!environment.getProperty("audigo.chat.es.text-contract", "baseline").equals("nfc-v1")) {
            throw new IllegalArgumentException("Server search sync requires the nfc-v1 text contract");
        }
    }

    public static URI base(Environment environment, boolean writer) {
        if (!isServer(environment)) {
            String value = environment.getProperty(writer ? "audigo.chat.search-sync.es-url" : "audigo.chat.es.url", LOCAL_URL);
            if (!LOCAL_URL.equals(value)) throw new IllegalArgumentException("Only the isolated local ES endpoint is allowed");
            return URI.create(value);
        }
        URI reader = parseBase(required(environment, "audigo.chat.es.url"));
        if (!writer) return reader;
        String configured = environment.getProperty("audigo.chat.search-sync.es-url", "");
        URI target = configured.isBlank() ? reader : parseBase(configured);
        if (!reader.equals(target)) throw new IllegalArgumentException("ES search and sync must use the same endpoint");
        return target;
    }

    /** Uses the JVM trust store for HTTPS; no insecure TLS override is installed. */
    public static URI parseBase(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ||
                    uri.getHost() == null || uri.getHost().equals("0.0.0.0") || uri.getHost().equals("[::]") ||
                    uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            // Normalize a trailing slash so equivalent reader/writer settings compare equally.
            return uri.resolve("/");
        } catch (IllegalArgumentException exception) {
            // Do not put an endpoint containing credentials into the exception or its cause.
            throw new IllegalArgumentException("ES URL must be an explicit HTTP(S) origin without credentials, path, query or fragment");
        }
    }

    public static String authorization(Environment environment, boolean writer) {
        boolean server = isServer(environment);
        String prefix = writer ? "audigo.chat.search-sync.es-" : "audigo.chat.es.";
        String username = server ? required(environment, prefix + "username")
                : environment.getProperty(prefix + "username", "elastic");
        String password = server ? required(environment, prefix + "password")
                : environment.getProperty(prefix + "password", environment.getProperty("audigo.chat.es.password", LOCAL_PASSWORD));
        if (username.contains(":") || username.contains("\r") || username.contains("\n") ||
                password.contains("\r") || password.contains("\n") || (server && password.equals(LOCAL_PASSWORD))) {
            throw new IllegalArgumentException("Invalid explicit ES Basic credentials; lab credentials are not allowed on a server");
        }
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing required setting: " + key);
        return value;
    }
}
