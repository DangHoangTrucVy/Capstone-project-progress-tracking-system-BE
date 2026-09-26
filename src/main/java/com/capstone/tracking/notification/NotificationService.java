package com.capstone.tracking.notification;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Every operation is scoped to the calling user's own notifications. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public Page<Notification> list(User user, boolean unreadOnly, Pageable pageable) {
        return unreadOnly
                ? notificationRepository.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(user.getId(), pageable)
                : notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), pageable);
    }

    public long unreadCount(User user) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(user.getId());
    }

    @Transactional
    public Notification markRead(UUID id, User user) {
        Notification notification = notificationRepository.findByIdAndRecipientId(id, user.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Notification", id));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return notification;
    }

    @Transactional
    public int markAllRead(User user) {
        return notificationRepository.markAllRead(user.getId(), Instant.now());
    }
}
