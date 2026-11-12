package com.audigo.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefreshSessionTokenTest {

    @Test
    void newTokenCanBeParsedAndRotatedWithoutChangingSessionId() {
        RefreshSessionToken original = RefreshSessionToken.create();
        RefreshSessionToken rotated = original.rotate();

        assertThat(RefreshSessionToken.parse(original.value())).contains(original);
        assertThat(rotated.sessionId()).isEqualTo(original.sessionId());
        assertThat(rotated.secret()).isNotEqualTo(original.secret());
        assertThat(rotated.secretHash()).isNotEqualTo(original.secretHash());
    }

    @Test
    void legacyAndMalformedTokensAreRejected() {
        assertThat(RefreshSessionToken.parse("AUDIGO_REFRESH_legacy")).isEmpty();
        assertThat(RefreshSessionToken.parse("invalid.token.extra")).isEmpty();
        assertThat(RefreshSessionToken.parse(null)).isEmpty();
    }
}
