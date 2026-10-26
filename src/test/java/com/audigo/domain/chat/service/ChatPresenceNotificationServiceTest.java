package com.audigo.domain.chat.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.dto.ChatPresenceRealtimeEvent;
import com.audigo.domain.chat.realtime.ChatRealtimePublisher;
import com.audigo.domain.matching.entity.MatchConnectionStatus;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatPresenceNotificationServiceTest {

    @Mock
    private MatchConnectionRepository matchConnectionRepository;

    @Mock
    private ChatRealtimePublisher realtimePublisher;

    @Test
    void publishes_presence_changes_once_to_each_active_counterpart() {
        when(matchConnectionRepository.findCounterpartUserIds(42L, MatchConnectionStatus.ACTIVE))
                .thenReturn(List.of(7L, 7L, 9L));

        ChatPresenceNotificationService service = new ChatPresenceNotificationService(
                matchConnectionRepository,
                realtimePublisher
        );

        service.publishStatusChange(42L, true);

        ChatPresenceRealtimeEvent event = ChatPresenceRealtimeEvent.statusChanged(42L, true);
        verify(realtimePublisher).publish(7L, event);
        verify(realtimePublisher).publish(9L, event);
        verifyNoMoreInteractions(realtimePublisher);
    }
}
