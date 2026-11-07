package com.audigo.domain.auth.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

record RefreshSessionToken(String sessionId, String secret) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    static RefreshSessionToken create() {
        return new RefreshSessionToken(randomPart(24), randomPart(32));
    }

    static Optional<RefreshSessionToken> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf('.');
        if (separator != 32 || value.length() != 76 || value.lastIndexOf('.') != separator) {
            return Optional.empty();
        }
        String sessionId = value.substring(0, separator);
        String secret = value.substring(separator + 1);
        if (!isUrlSafe(sessionId) || !isUrlSafe(secret)) {
            return Optional.empty();
        }
        return Optional.of(new RefreshSessionToken(sessionId, secret));
    }

    RefreshSessionToken rotate() {
        return new RefreshSessionToken(sessionId, randomPart(32));
    }

    String value() {
        return sessionId + "." + secret;
    }

    String secretHash() {
        return TokenHash.sha256(secret);
    }

    private static String randomPart(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private static boolean isUrlSafe(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }
}
