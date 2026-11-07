package com.audigo.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;

record RefreshSessionData(
        @JsonProperty("userId") Long userId,
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("secretHash") String secretHash
) {
}
