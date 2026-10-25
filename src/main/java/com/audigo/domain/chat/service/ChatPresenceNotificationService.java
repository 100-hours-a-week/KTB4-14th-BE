package com.audigo.domain.chat.service;

import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatPresenceNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ChatPresenceNotificationService.class);
    private final MatchConnectionRepository matchConnectionRepository;
    private final ChatRealtimePublisher realtimePublisher;

    public ChatPresenceNotificationService(
            MatchConnectionRepository matchConnectionRepository,
            ChatRealtimePublisher realtimePublisher
    ) {
        this.matchConnectionRepository = matchConnectionRepository;
        this.realtimePublisher = realtimePublisher;
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
                realtimePublisher.publish(recipientUserId, event);
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
