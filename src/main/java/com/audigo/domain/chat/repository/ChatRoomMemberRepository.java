package com.audigo.domain.chat.repository;

import com.audigo.domain.chat.entity.ChatRoomMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    Optional<ChatRoomMember> findByChatRoom_IdAndUser_IdAndDeletedAtIsNull(Long roomId, Long userId);

    @Query("""
            select member.user.id
            from ChatRoomMember member
            where member.chatRoom.id = :roomId
              and member.user.id <> :userId
              and member.deletedAt is null
            """)
    List<Long> findActiveOpponentUserIds(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
