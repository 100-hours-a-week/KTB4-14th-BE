package com.audigo.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatMessageResponse;
import com.audigo.domain.chat.dto.SendChatMessageRequest.SendChatMessageCommand;
import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomMember;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatMessageRepository;
import com.audigo.domain.chat.repository.ChatRoomDetailRepository;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.error.RateLimitExceededException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    private static final Long ROOM_ID = 54L;
    private static final Long SENDER_ID = 1L;
    private static final Long RECIPIENT_ID = 2L;
    private static final String CLIENT_MESSAGE_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatRoomDetailRepository chatRoomDetailRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatRealtimePublisher realtimePublisher;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ChatRoom room;

    @Mock
    private ChatRoomMember senderMember;

    @Mock
    private User sender;

    @InjectMocks
    private ChatMessageService service;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void persists_new_message_then_publishes_realtime_event_after_commit() {
        stubNewMessagePath(0);
        when(room.getId()).thenReturn(ROOM_ID);
        when(sender.id()).thenReturn(SENDER_ID);
        when(sender.nickname()).thenReturn("상대방");
        when(room.recordMessage(any(LocalDateTime.class))).thenReturn(7L);
        when(chatMessageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();

        ChatMessageResponse response = service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "hello")
        );

        assertThat(response.roomId()).isEqualTo(ROOM_ID);
        assertThat(response.senderId()).isEqualTo(SENDER_ID);
        assertThat(response.clientMessageId()).isEqualTo(CLIENT_MESSAGE_ID);
        assertThat(response.messageSequence()).isEqualTo(7L);
        assertThat(response.content()).isEqualTo("hello");

        InOrder order = inOrder(chatRoomRepository, chatRoomMemberRepository, chatMessageRepository);
        order.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
        order.verify(chatRoomMemberRepository).findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(ROOM_ID, SENDER_ID);
        order.verify(chatMessageRepository).findByChatRoom_IdAndSender_IdAndClientMessageId(
                ROOM_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID
        );
        order.verify(chatMessageRepository).saveAndFlush(any(ChatMessage.class));

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        verify(realtimePublisher, never()).publish(eq(RECIPIENT_ID), any(ChatMessageRealtimeEvent.class));
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(realtimePublisher).publish(
                eq(RECIPIENT_ID),
                any(ChatMessageRealtimeEvent.class)
        );
        verify(notificationService).notifyNewMessage(
                RECIPIENT_ID,
                ROOM_ID,
                "상대방님이 메시지를 보냈습니다"
        );
    }

    @Test
    void does_not_create_another_notification_when_the_room_already_has_unread_messages() {
        stubNewMessagePath(0);
        when(chatMessageRepository.countUnreadMessages(ROOM_ID, RECIPIENT_ID, ChatMessageStatus.SENT))
                .thenReturn(1L);
        when(room.getId()).thenReturn(ROOM_ID);
        when(sender.id()).thenReturn(SENDER_ID);
        when(room.recordMessage(any(LocalDateTime.class))).thenReturn(8L);
        when(chatMessageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();

        service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "second message")
        );

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(notificationService, never()).notifyNewMessage(any(), any(), any());
    }

    @Test
    void ended_room_takes_precedence_over_idempotent_replay() {
        stubRoomAndMember();
        when(room.getStatus()).thenReturn(ChatRoomStatus.ENDED);

        assertThatThrownBy(() -> service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "hello")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.CHAT_ROOM_ENDED)
        );

        verify(chatMessageRepository, never()).findByChatRoom_IdAndSender_IdAndClientMessageId(
                ROOM_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID
        );
        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(realtimePublisher, never()).publish(eq(RECIPIENT_ID), any(ChatMessageRealtimeEvent.class));
    }

    @Test
    void idempotent_replay_returns_existing_message_without_counting_toward_limit() {
        stubRoomAndMember();
        when(room.getStatus()).thenReturn(ChatRoomStatus.ACTIVE);
        ChatMessage existing = ChatMessage.create(
                room,
                sender,
                CLIENT_MESSAGE_ID,
                "hello",
                4L,
                LocalDateTime.of(2026, 10, 12, 5, 0)
        );
        when(room.getId()).thenReturn(ROOM_ID);
        when(sender.id()).thenReturn(SENDER_ID);
        when(chatMessageRepository.findByChatRoom_IdAndSender_IdAndClientMessageId(
                ROOM_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID
        )).thenReturn(Optional.of(existing));

        ChatMessageResponse response = service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "hello")
        );

        assertThat(response.messageSequence()).isEqualTo(4L);
        assertThat(response.content()).isEqualTo("hello");
        verify(chatMessageRepository, never()).countSentBySenderSince(
                eq(SENDER_ID),
                eq(ChatMessageStatus.SENT),
                any(LocalDateTime.class)
        );
        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
        verify(realtimePublisher, never()).publish(eq(RECIPIENT_ID), any(ChatMessageRealtimeEvent.class));
    }

    @Test
    void conflicting_idempotency_key_is_rejected_before_rate_limit_or_save() {
        stubRoomAndMember();
        when(room.getStatus()).thenReturn(ChatRoomStatus.ACTIVE);
        ChatMessage existing = ChatMessage.create(
                room,
                sender,
                CLIENT_MESSAGE_ID,
                "different content",
                4L,
                LocalDateTime.of(2026, 10, 12, 5, 0)
        );
        when(chatMessageRepository.findByChatRoom_IdAndSender_IdAndClientMessageId(
                ROOM_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "hello")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT)
        );

        verify(chatMessageRepository, never()).countSentBySenderSince(
                eq(SENDER_ID),
                eq(ChatMessageStatus.SENT),
                any(LocalDateTime.class)
        );
        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
    }

    @Test
    void rejects_the_eleventh_message_in_the_sliding_window_with_retry_delay() {
        stubNewMessagePath(10);
        when(chatMessageRepository.findOldestSentAtBySenderSince(
                eq(SENDER_ID),
                eq(ChatMessageStatus.SENT),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30)));

        assertThatThrownBy(() -> service.send(
                SENDER_ID,
                ROOM_ID,
                new SendChatMessageCommand(CLIENT_MESSAGE_ID, "hello")
        )).isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> assertThat(((RateLimitExceededException) exception).retryAfterSeconds())
                        .isBetween(1L, 31L));

        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessage.class));
    }

    private void stubNewMessagePath(long sentMessageCount) {
        stubRoomAndMember();
        when(room.getStatus()).thenReturn(ChatRoomStatus.ACTIVE);
        when(chatMessageRepository.findByChatRoom_IdAndSender_IdAndClientMessageId(
                ROOM_ID,
                SENDER_ID,
                CLIENT_MESSAGE_ID
        )).thenReturn(Optional.empty());
        when(chatRoomMemberRepository.findActiveOpponentUserIds(ROOM_ID, SENDER_ID))
                .thenReturn(List.of(RECIPIENT_ID));
        when(userRepository.findByIdForChatRateLimit(SENDER_ID)).thenReturn(Optional.of(sender));
        when(sender.isActive()).thenReturn(true);
        when(chatMessageRepository.countSentBySenderSince(
                eq(SENDER_ID),
                eq(ChatMessageStatus.SENT),
                any(LocalDateTime.class)
        )).thenReturn(sentMessageCount);
    }

    private void stubRoomAndMember() {
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID)).thenReturn(Optional.of(room));
        when(chatRoomMemberRepository.findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(ROOM_ID, SENDER_ID))
                .thenReturn(Optional.of(senderMember));
    }
}
