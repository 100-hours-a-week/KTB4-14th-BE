package com.audigo.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ChatMessageContextResponse(
        List<ChatRoomDetailResponse.Message> chats,
        @JsonProperty("has_older") boolean hasOlder,
        @JsonProperty("has_newer") boolean hasNewer
) {
}
