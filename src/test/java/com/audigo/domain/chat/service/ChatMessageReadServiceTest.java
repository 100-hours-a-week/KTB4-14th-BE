package com.audigo.domain.chat.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatReadReceiptRealtimeEvent;
import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomMember;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.chat.repository.ChatMessageReadRepository;
import com.audigo.domain.chat.repository.ChatMessageRepository;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ChatMessageReadServiceTest {

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatRoomMemberRepository chatRoomMemberRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ChatMessageReadRepository chatMessageReadRepository;
    @Mock private ChatRealtimePublisher realtimePublisher;
    @Mock private ChatRoom room;
    @Mock private ChatRoomMember member;
    @Mock private ChatMessage boundary;
    @InjectMocks private ChatMessageReadService service;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void read_receipt_is_published_only_after_commit() {
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 25, 2, 0);
        when(chatRoomRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(room));
        when(chatRoomMemberRepository.findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(12L, 7L))
                .thenReturn(Optional.of(member));
        when(chatMessageRepository.findByIdAndChatRoom_IdAndStatus(91L, 12L, ChatMessageStatus.SENT))
                .thenReturn(Optional.of(boundary));
        when(boundary.getMessageSequence()).thenReturn(5L);
        when(chatMessageReadRepository.findLatestReadAtThrough(12L, 7L, 5L, ChatMessageStatus.SENT))
                .thenReturn(Optional.of(readAt));
        when(room.getStatus()).thenReturn(ChatRoomStatus.ACTIVE);
        when(room.getId()).thenReturn(12L);
        when(chatRoomMemberRepository.findActiveOpponentUserIds(12L, 7L)).thenReturn(List.of(3L));
        TransactionSynchronizationManager.initSynchronization();

        service.markRead(7L, 12L, 91L);

        verify(realtimePublisher, never()).publish(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.any(ChatReadReceiptRealtimeEvent.class)
        );
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(realtimePublisher).publish(
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.<ChatReadReceiptRealtimeEvent>argThat(event ->
                        event.readThroughMessageSequence() == 5L
                        && event.readAt().toLocalDateTime().equals(readAt))
        );
    }
}
