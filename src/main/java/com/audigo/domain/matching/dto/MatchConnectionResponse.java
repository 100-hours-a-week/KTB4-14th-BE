package com.audigo.domain.matching.dto;

import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record MatchConnectionResponse(
        @JsonProperty("match_connection_id") Long matchConnectionId,
        @JsonProperty("matching_request_id") Long matchingRequestId,
        @JsonProperty("target_user_id") Long targetUserId,
        MatchConnectionStatus status,
        @JsonProperty("selected_at") LocalDateTime selectedAt
) {

    public static MatchConnectionResponse from(MatchConnection connection) {
        return new MatchConnectionResponse(
                connection.getId(),
                connection.getMatchingRequest().getId(),
                connection.getTargetUser().id(),
                connection.getStatus(),
                connection.getSelectedAt()
        );
    }
}
