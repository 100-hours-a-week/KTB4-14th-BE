package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatMessageCatchUpQuery;
import com.audigo.domain.chat.dto.ChatMessageCatchUpResponse;
import com.audigo.domain.chat.dto.ChatMessageRealtimeEvent;
import com.audigo.domain.chat.dto.ChatMessageResponse;
import com.audigo.domain.chat.dto.SendChatMessageRequest.SendChatMessageCommand;
import com.audigo.domain.chat.entity.ChatMessage;
import com.audigo.domain.chat.entity.ChatMessageStatus;
import com.audigo.domain.chat.entity.ChatRoom;
import com.audigo.domain.chat.entity.ChatRoomMember;
import com.audigo.domain.chat.entity.ChatRoomStatus;
import com.audigo.domain.chat.repository.ChatMessageRepository;
import com.audigo.domain.chat.repository.ChatRoomMemberRepository;
import com.audigo.domain.chat.repository.ChatRoomRepository;
import com.audigo.domain.notification.service.NotificationService;
import com.audigo.domain.user.entity.User;
import com.audigo.domain.user.repository.UserRepository;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import com.audigo.global.error.RateLimitExceededException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);
    private static final int MAX_MESSAGES_PER_WINDOW = 10;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);
    private static final String RECIPIENT_DESTINATION = "/queue/chat/messages";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;

    public ChatMessageService(
            ChatRoomRepository chatRoomRepository,
            ChatRoomMemberRepository chatRoomMemberRepository,
            ChatMessageRepository chatMessageRepository,
            UserRepository userRepository,
            SimpMessagingTemplate messagingTemplate,
            NotificationService notificationService
    ) {
        this.chatRoomRepository = chatRoomRepository;
        this.chatRoomMemberRepository = chatRoomMemberRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.notificationService = notificationService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChatMessageResponse send(Long userId, Long roomId, SendChatMessageCommand command) {
        return sendInternal(userId, roomId, command).response();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SendResult sendWithStatus(Long userId, Long roomId, SendChatMessageCommand command) {
        return sendInternal(userId, roomId, command);
    }

    private SendResult sendInternal(Long userId, Long roomId, SendChatMessageCommand command) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        ChatRoomMember senderMember = chatRoomMemberRepository
                .findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (room.getStatus() == ChatRoomStatus.ENDED) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ENDED);
        }

        Optional<ChatMessage> existingMessage = chatMessageRepository
                .findByChatRoom_IdAndSender_IdAndClientMessageId(roomId, userId, command.clientMessageId());
        if (existingMessage.isPresent()) {
            ChatMessage existing = existingMessage.get();
            if (!existing.getContent().equals(command.content())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new SendResult(toResponse(existing), false);
        }

        List<Long> activeOpponentIds = chatRoomMemberRepository.findActiveOpponentUserIds(roomId, userId);
        if (activeOpponentIds.size() != 1) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_ENDED);
        }
        Long recipientUserId = activeOpponentIds.getFirst();

        // Locking the sender's user row serializes rate checks across different chat rooms.
        User sender = userRepository.findByIdForChatRateLimit(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!sender.isActive()) {
            throw new BusinessException(ErrorCode.USER_INACTIVE);
        }

        LocalDateTime sentAt = LocalDateTime.now(ZoneOffset.UTC);
        enforceRateLimit(userId, sentAt);
        boolean shouldNotifyRecipient = chatMessageRepository.countUnreadMessages(
                roomId,
                recipientUserId,
                ChatMessageStatus.SENT
        ) == 0;

        long messageSequence = room.recordMessage(sentAt);
        ChatMessage message = chatMessageRepository.saveAndFlush(ChatMessage.create(
                room,
                sender,
                command.clientMessageId(),
                command.content(),
                messageSequence,
                sentAt
        ));
        ChatMessageResponse response = toResponse(message);
        publishAfterCommit(recipientUserId, sender.nickname(), response, shouldNotifyRecipient);
        return new SendResult(response, true);
    }

    @Transactional(readOnly = true)
    public ChatMessageCatchUpResponse catchUp(Long userId, ChatMessageCatchUpQuery query) {
        chatRoomMemberRepository.findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(query.roomId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatMessage> fetched = chatMessageRepository
                .findByChatRoom_IdAndStatusAndMessageSequenceGreaterThanOrderByMessageSequenceAsc(
                        query.roomId(),
                        ChatMessageStatus.SENT,
                        query.afterMessageSequence(),
                        PageRequest.of(0, query.size() + 1)
                );
        boolean hasNext = fetched.size() > query.size();
        List<ChatMessage> page = hasNext ? fetched.subList(0, query.size()) : fetched;
        List<ChatMessageResponse> responses = new ArrayList<>(page.size());
        for (ChatMessage message : page) {
            responses.add(toResponse(message));
        }
        long nextAfterMessageSequence = page.isEmpty()
                ? query.afterMessageSequence()
                : page.getLast().getMessageSequence();
        return new ChatMessageCatchUpResponse(
                responses,
                responses.size(),
                nextAfterMessageSequence,
                hasNext
        );
    }

    private void enforceRateLimit(Long userId, LocalDateTime now) {
        LocalDateTime windowStart = now.minus(RATE_LIMIT_WINDOW);
        long count = chatMessageRepository.countSentBySenderSince(userId, ChatMessageStatus.SENT, windowStart);
        if (count < MAX_MESSAGES_PER_WINDOW) {
            return;
        }

        LocalDateTime oldestSentAt = chatMessageRepository
                .findOldestSentAtBySenderSince(userId, ChatMessageStatus.SENT, windowStart)
                .orElse(now);
        LocalDateTime nextAllowedAt = oldestSentAt.plus(RATE_LIMIT_WINDOW);
        long retryAfterMillis = Math.max(1, Duration.between(now, nextAllowedAt).toMillis());
        long retryAfterSeconds = (retryAfterMillis + 999) / 1000;
        throw new RateLimitExceededException(retryAfterSeconds);
    }

    private void publishAfterCommit(
            Long recipientUserId,
            String senderNickname,
            ChatMessageResponse response,
            boolean shouldNotifyRecipient
    ) {
        ChatMessageRealtimeEvent event = ChatMessageRealtimeEvent.messageCreated(response);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishRealtimeMessage(recipientUserId, response, event);
                if (shouldNotifyRecipient) {
                    createNewMessageNotification(recipientUserId, senderNickname, response);
                }
            }
        });
    }

    private void publishRealtimeMessage(
            Long recipientUserId,
            ChatMessageResponse response,
            ChatMessageRealtimeEvent event
    ) {
        try {
            messagingTemplate.convertAndSendToUser(
                    recipientUserId.toString(),
                    RECIPIENT_DESTINATION,
                    event
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "chat_message_realtime_publish_failed room_id={} message_id={} recipient_id={}",
                    response.roomId(),
                    response.messageId(),
                    recipientUserId,
                    exception
            );
        }
    }

    private void createNewMessageNotification(
            Long recipientUserId,
            String senderNickname,
            ChatMessageResponse response
    ) {
        try {
            notificationService.notifyNewMessage(
                    recipientUserId,
                    response.roomId(),
                    senderNickname + "님이 메시지를 보냈습니다"
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "chat_message_notification_create_failed room_id={} message_id={} recipient_id={}",
                    response.roomId(),
                    response.messageId(),
                    recipientUserId,
                    exception
            );
        }
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getChatRoom().getId(),
                message.getSender().id(),
                message.getClientMessageId(),
                message.getMessageSequence(),
                message.getContent(),
                message.getSentAt().atOffset(ZoneOffset.UTC)
        );
    }

    public record SendResult(ChatMessageResponse response, boolean created) {
    }
}
