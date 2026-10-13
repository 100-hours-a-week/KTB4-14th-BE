package com.audigo.domain.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatRoomDetailQueryTest {

    @Test
    void parses_room_query_and_normalizes_cursor_to_utc() {
        ChatRoomDetailQuery query = ChatRoomDetailQuery.from(
                "54",
                null,
                "2026-09-04T17:00:20+09:00",
                "101"
        );

        assertThat(query.roomId()).isEqualTo(54L);
        assertThat(query.size()).isNull();
        assertThat(query.cursorTime()).isEqualTo(LocalDateTime.of(2026, 9, 4, 8, 0, 20));
        assertThat(query.cursorId()).isEqualTo(101L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void rejects_invalid_room_ids(String roomId) {
        assertInvalidRequest(() -> ChatRoomDetailQuery.from(roomId, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "not-a-number"})
    void rejects_invalid_page_sizes(String size) {
        assertInvalidRequest(() -> ChatRoomDetailQuery.from("54", size, null, null));
    }

    @Test
    void requires_both_cursor_values_together() {
        assertInvalidRequest(() -> ChatRoomDetailQuery.from("54", null, "2026-09-04T08:00:20Z", null));
        assertInvalidRequest(() -> ChatRoomDetailQuery.from("54", null, null, "101"));
    }

    @Test
    void requires_cursor_time_to_include_a_timezone() {
        assertInvalidRequest(() -> ChatRoomDetailQuery.from("54", null, "2026-09-04T08:00:20", "101"));
    }

    private void assertInvalidRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        assertThatThrownBy(request)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST)
                );
    }
}
