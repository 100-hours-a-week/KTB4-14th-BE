package com.audigo.domain.chat.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import org.junit.jupiter.api.Test;

class SendChatMessageRequestTest {

    private static final String CLIENT_MESSAGE_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void trims_unicode_whitespace_around_content() {
        SendChatMessageRequest.SendChatMessageCommand command = new SendChatMessageRequest(
                CLIENT_MESSAGE_ID,
                "\u2003 hello \u2003"
        ).toCommand();

        assertThat(command.clientMessageId()).isEqualTo(CLIENT_MESSAGE_ID);
        assertThat(command.content()).isEqualTo("hello");
    }

    @Test
    void counts_unicode_code_points_instead_of_utf16_code_units() {
        SendChatMessageRequest.SendChatMessageCommand command = new SendChatMessageRequest(
                CLIENT_MESSAGE_ID,
                "😀".repeat(500)
        ).toCommand();

        assertThat(command.content().codePointCount(0, command.content().length())).isEqualTo(500);
    }

    @Test
    void rejects_blank_or_more_than_500_code_points() {
        assertInvalidRequest(new SendChatMessageRequest(CLIENT_MESSAGE_ID, " \t\u2003 "));
        assertInvalidRequest(new SendChatMessageRequest(CLIENT_MESSAGE_ID, "😀".repeat(501)));
    }

    @Test
    void rejects_non_uuid_client_message_id() {
        assertInvalidRequest(new SendChatMessageRequest("not-a-uuid", "hello"));
    }

    private void assertInvalidRequest(SendChatMessageRequest request) {
        assertThatThrownBy(request::toCommand)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST)
                );
    }
}
