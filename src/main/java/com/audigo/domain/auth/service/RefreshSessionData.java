package com.audigo.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record RefreshSessionData(
        @JsonProperty("userId") Long userId,
        @JsonProperty("secretHash") String secretHash
) {
}
