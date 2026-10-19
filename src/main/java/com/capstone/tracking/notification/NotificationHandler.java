package com.capstone.tracking.notification;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.notification.dto.NotificationResponse;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.notification.email.EmailOutbox;
import com.capstone.tracking.notification.email.EmailOutboxRepository;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns a {@link DomainEvent} into one in-app notification per recipient, broadcasts them to open notification
 * streams (after commit) and, for {@link DomainEventType#isEmailed() emailed} types, queues one email per recipient in
 * the {@link EmailOutbox} in this same transaction (sent later by the dispatcher, so SMTP never delays a request).
 * Runs after the business transaction committed (in-process) or from the SQS consumer, so it opens its own
 * transaction. Idempotent per (event, recipient), since SQS delivers at least once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHandler {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationBroadcaster broadcaster;
    private final EmailOutboxRepository emailOutboxRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DomainEvent event) {
        Set<UUID> recipients = new LinkedHashSet<>(event.recipients() == null ? List.of() : event.recipients());
        recipients.remove(null);
        recipients.remove(event.actorId()); // nobody needs to be told about their own action

        String message = truncate(event.message(), 500);
        List<Notification> created = new ArrayList<>();
        for (UUID recipientId : recipients) {
            User recipient = userRepository.findById(recipientId).orElse(null);
            if (recipient == null || notificationRepository.existsByEventIdAndRecipientId(event.eventId(), recipientId)) {
                continue;
            }
            created.add(notificationRepository.save(Notification.builder()
                    .eventId(event.eventId())
                    .recipient(recipient)
                    .type(event.type())
                    .message(message)
                    .details(event.details())
                    .deadline(event.deadline())
                    .groupId(event.groupId())
                    .entityId(event.entityId())
                    .build()));
            if (event.type().isEmailed()
                    && !emailOutboxRepository.existsByEventIdAndRecipientsTo(event.eventId(), recipient.getEmail())) {
                emailOutboxRepository.save(EmailOutbox.of(event.eventId(), email(recipient, event, message)));
            }
        }
        if (created.isEmpty()) {
            return;
        }
        List<Runnable> afterCommit = new ArrayList<>();
        for (Notification n : created) {
            UUID recipientId = n.getRecipient().getId();
            NotificationResponse response = NotificationResponse.from(n);
            afterCommit.add(() -> broadcaster.broadcast(recipientId, response));
        }
        runAfterCommit(afterCommit);
    }

    private static EmailMessage email(User recipient, DomainEvent event, String message) {
        StringBuilder body = new StringBuilder()
                .append("Xin chào ").append(recipient.getFullName()).append(",\n\n")
                .append(message).append('\n');
        if (event.details() != null && !event.details().isBlank()) {
            body.append("Chi tiết: ").append(event.details()).append('\n');
        }
        if (event.deadline() != null) {
            body.append("Hạn chót: ").append(VnTime.format(event.deadline())).append('\n');
        }
        body.append("\nVui lòng đăng nhập Website ghép nhóm để xem chi tiết.");
        return new EmailMessage(List.of(recipient.getEmail()), List.of(),
                "[Ghép nhóm] " + truncate(message, 150), body.toString());
    }

    private void runAfterCommit(List<Runnable> actions) {
        Runnable all = () -> actions.forEach(action -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Notification delivery step failed", e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    all.run();
                }
            });
        } else {
            all.run();
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
