package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatReadReceiptRealtimeEvent;
import com.audigo.domain.chat.dto.ChatRoomReadResponse;
import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatMessageReadRepository;
import com.audigo.domain.chat.repository.ChatMessageRepository;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ChatMessageReadService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageReadService.class);

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageReadRepository chatMessageReadRepository;
    private final ChatRealtimePublisher realtimePublisher;

    public ChatMessageReadService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomMemberRepository chatRoomMemberRepository,
            ChatMessageRepository chatMessageRepository,
            ChatMessageReadRepository chatMessageReadRepository,
            ChatRealtimePublisher realtimePublisher
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatRoomMemberRepository = chatRoomMemberRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatMessageReadRepository = chatMessageReadRepository;
        this.realtimePublisher = realtimePublisher;
    }

    @Transactional
    public ChatRoomReadResponse markRead(Long userId, Long roomId, Long lastReadChatId) {
        if (userId == null || roomId == null || roomId < 1 || lastReadChatId == null || lastReadChatId < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        chatRoomMemberRepository.findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatMessage boundary = chatMessageRepository
                .findByIdAndChatRoom_IdAndStatus(lastReadChatId, roomId, ChatMessageStatus.SENT)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));

        LocalDateTime readAt = LocalDateTime.now(ZoneOffset.UTC);
        int readCount = chatMessageReadRepository.insertUnreadMessagesThrough(
                roomId,
                userId,
                boundary.getMessageSequence(),
                readAt
        );
        OffsetDateTime latestReadAt = chatMessageReadRepository.findLatestReadAtThrough(
                        roomId,
                        userId,
                        boundary.getMessageSequence(),
                        ChatMessageStatus.SENT
                )
                .map(value -> value.atOffset(ZoneOffset.UTC))
                .orElse(null);

        publishReadReceiptAfterCommit(room, userId, boundary.getMessageSequence(), latestReadAt);

        return new ChatRoomReadResponse(
                roomId,
                boundary.getId(),
                readCount,
                latestReadAt
        );
    }

    private void publishReadReceiptAfterCommit(
            ChatRoom room,
            Long readerId,
            long readThroughMessageSequence,
            OffsetDateTime readAt
    ) {
        if (room.getStatus() != ChatRoomStatus.ACTIVE || readAt == null) {
            return;
        }

        List<Long> opponentUserIds = chatRoomMemberRepository.findActiveOpponentUserIds(room.getId(), readerId);
        if (opponentUserIds.size() != 1) {
            return;
        }

        Long recipientUserId = opponentUserIds.getFirst();
        ChatReadReceiptRealtimeEvent event = ChatReadReceiptRealtimeEvent.updated(
                room.getId(),
                readerId,
                readThroughMessageSequence,
                readAt
        );
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    realtimePublisher.publish(recipientUserId, event);
                } catch (RuntimeException exception) {
                    log.warn(
                            "chat_read_receipt_realtime_publish_failed room_id={} reader_id={} recipient_id={}",
                            room.getId(),
                            readerId,
                            recipientUserId,
                            exception
                    );
                }
            }
        });
    }
}
