package com.audigo.domain.matching.repository;

import com.audigo.domain.matching.entity.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByMatchConnection_Id(Long matchConnectionId);
}
