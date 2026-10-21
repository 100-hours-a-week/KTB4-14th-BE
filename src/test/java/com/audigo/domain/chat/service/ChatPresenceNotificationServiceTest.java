package com.audigo.domain.chat.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ChatPresenceNotificationServiceTest {

    private static final String RECIPIENT_DESTINATION = "/queue/chat/messages";

    @Mock
    private MatchConnectionRepository matchConnectionRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void publishes_presence_changes_once_to_each_active_counterpart() {
        when(matchConnectionRepository.findCounterpartUserIds(42L, MatchConnectionStatus.ACTIVE))
                .thenReturn(List.of(7L, 7L, 9L));

        ChatPresenceNotificationService service = new ChatPresenceNotificationService(
                matchConnectionRepository,
                messagingTemplate
        );

        service.publishStatusChange(42L, true);

        ChatPresenceRealtimeEvent event = ChatPresenceRealtimeEvent.statusChanged(42L, true);
        verify(messagingTemplate).convertAndSendToUser("7", RECIPIENT_DESTINATION, event);
        verify(messagingTemplate).convertAndSendToUser("9", RECIPIENT_DESTINATION, event);
        verifyNoMoreInteractions(messagingTemplate);
    }
}
