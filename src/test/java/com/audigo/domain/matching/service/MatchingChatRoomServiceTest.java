package com.audigo.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.audigo.domain.matching.dto.MatchingChatRoomResponse;
import com.audigo.domain.matching.entity.ChatRoom;
import com.audigo.domain.matching.entity.ChatRoomStatus;
import com.audigo.domain.matching.entity.MatchConnection;
import com.audigo.domain.matching.entity.MatchingRequest;
import com.audigo.domain.matching.entity.PreferredCompanionGender;
import com.audigo.domain.matching.repository.ChatRoomRepository;
import com.audigo.domain.matching.repository.MatchConnectionRepository;
import com.audigo.domain.travel.entity.TravelPaceType;
import com.audigo.domain.travel.entity.TravelThemeType;
import com.audigo.domain.user.entity.User;
import com.audigo.global.error.BusinessException;
import com.audigo.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MatchingChatRoomServiceTest {

    @Mock
    private MatchConnectionRepository matchConnectionRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private MatchingChatRoomService matchingChatRoomService;

    @Test
    void prepares_new_chat_room_for_match_connection() {
        MatchConnection connection = matchConnection();
        when(matchConnectionRepository.findById(30L)).thenReturn(Optional.of(connection));
        when(chatRoomRepository.findByMatchConnection_Id(30L)).thenReturn(Optional.empty());
        when(chatRoomRepository.save(any(ChatRoom.class))).thenAnswer(invocation -> {
            ChatRoom chatRoom = invocation.getArgument(0);
            ReflectionTestUtils.setField(chatRoom, "id", 40L);
            return chatRoom;
        });

        MatchingChatRoomResponse response = matchingChatRoomService.prepareChatRoom(1L, 30L);

        assertThat(response.chatRoomId()).isEqualTo(40L);
        assertThat(response.matchConnectionId()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(response.memberUserIds()).containsExactly(1L, 22L);
        verify(chatRoomRepository).save(any(ChatRoom.class));
    }

    @Test
    void returns_existing_chat_room_for_match_connection() {
        MatchConnection connection = matchConnection();
        ChatRoom existing = ChatRoom.create(
                connection,
                "윤혁 · 매칭후보",
                connection.getMatchingRequest().getRequester(),
                connection.getTargetUser()
        );
        ReflectionTestUtils.setField(existing, "id", 40L);
        when(matchConnectionRepository.findById(30L)).thenReturn(Optional.of(connection));
        when(chatRoomRepository.findByMatchConnection_Id(30L)).thenReturn(Optional.of(existing));

        MatchingChatRoomResponse response = matchingChatRoomService.prepareChatRoom(22L, 30L);

        assertThat(response.chatRoomId()).isEqualTo(40L);
        assertThat(response.memberUserIds()).containsExactly(1L, 22L);
        verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    }

    @Test
    void rejects_when_match_connection_not_found() {
        when(matchConnectionRepository.findById(30L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchingChatRoomService.prepareChatRoom(1L, 30L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.MATCH_CONNECTION_NOT_FOUND)
                );
    }

    @Test
    void rejects_when_user_is_not_match_connection_member() {
        when(matchConnectionRepository.findById(30L)).thenReturn(Optional.of(matchConnection()));

        assertThatThrownBy(() -> matchingChatRoomService.prepareChatRoom(99L, 30L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );
    }

    private MatchConnection matchConnection() {
        MatchingRequest matchingRequest = MatchingRequest.create(
                activeUser(1L, "윤혁"),
                PreferredCompanionGender.FEMALE,
                TravelPaceType.BALANCED,
                100_000,
                800_000,
                List.of(TravelThemeType.NATURE, TravelThemeType.FOOD)
        );
        ReflectionTestUtils.setField(matchingRequest, "id", 10L);
        MatchConnection connection = MatchConnection.create(matchingRequest, activeUser(22L, "매칭후보"));
        ReflectionTestUtils.setField(connection, "id", 30L);
        return connection;
    }

    private User activeUser(Long id, String nickname) {
        User user = User.create(nickname, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
