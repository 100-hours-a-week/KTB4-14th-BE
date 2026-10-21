package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatPresenceNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ChatPresenceNotificationService.class);
    private static final String RECIPIENT_DESTINATION = "/queue/chat/messages";

    private final MatchConnectionRepository matchConnectionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatPresenceNotificationService(
            MatchConnectionRepository matchConnectionRepository,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.matchConnectionRepository = matchConnectionRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void publishStatusChange(Long userId, boolean online) {
        Set<Long> counterpartUserIds;
        try {
            counterpartUserIds = new LinkedHashSet<>(matchConnectionRepository.findCounterpartUserIds(
                    userId,
                    MatchConnectionStatus.ACTIVE
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "chat_presence_realtime_recipient_lookup_failed user_id={} is_online={}",
                    userId,
                    online,
                    exception
            );
            return;
        }

        ChatPresenceRealtimeEvent event = ChatPresenceRealtimeEvent.statusChanged(userId, online);

        for (Long recipientUserId : counterpartUserIds) {
            try {
                messagingTemplate.convertAndSendToUser(
                        recipientUserId.toString(),
                        RECIPIENT_DESTINATION,
                        event
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "chat_presence_realtime_publish_failed user_id={} recipient_id={} is_online={}",
                        userId,
                        recipientUserId,
                        online,
                        exception
                );
            }
        }
    }
}
