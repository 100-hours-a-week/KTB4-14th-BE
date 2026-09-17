package com.audigo.domain.notification.repository;

import com.audigo.domain.notification.entity.Notification;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUser_IdOrderByCreatedAtDesc(Long userId);

    long countByUser_IdAndReadFalse(Long userId);

    Optional<Notification> findByIdAndUser_Id(Long id, Long userId);

    List<Notification> findByUser_IdAndReadFalse(Long userId);

    List<Notification> findByIdInAndUser_Id(Collection<Long> ids, Long userId);
}
