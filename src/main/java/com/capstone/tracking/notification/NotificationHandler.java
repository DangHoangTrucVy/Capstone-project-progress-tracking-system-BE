package com.capstone.tracking.notification;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a {@link DomainEvent} into in-app notifications for the people who should hear about it. Runs after the
 * business transaction committed (in-process) or from the SQS consumer, so it always opens its own transaction.
 * Idempotent per (event, recipient): SQS delivers at least once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHandler {

    private final NotificationRepository notificationRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DomainEvent event) {
        Optional<StudentGroup> maybeGroup = studentGroupRepository.findById(event.groupId());
        if (maybeGroup.isEmpty()) {
            log.warn("Dropping {} {}: group {} no longer exists", event.type(), event.eventId(), event.groupId());
            return;
        }
        StudentGroup group = maybeGroup.get();
        String code = group.getGroupCode();

        Set<UUID> recipients = new LinkedHashSet<>();
        String message = switch (event.type()) {
            case DOCUMENT_SUBMITTED -> {
                addSupervisor(group, recipients);
                yield "Nhóm " + code + " đã nộp tài liệu \"" + event.label() + "\"";
            }
            case BOOKING_CONFIRMED -> {
                recipients.add(event.instructorId());
                yield "Nhóm " + code + " đã đặt lịch gặp " + event.label();
            }
            case BOOKING_CANCELLED -> {
                recipients.add(event.instructorId());
                yield "Nhóm " + code + " đã huỷ lịch gặp " + event.label();
            }
            case PROGRESS_REPORTED -> {
                addSupervisor(group, recipients);
                yield "Nhóm " + code + " đã cập nhật tiến độ " + event.label();
            }
            case PROGRESS_FEEDBACK -> {
                groupMemberRepository.findByGroupIdAndStatus(group.getId(), MemberStatus.ACTIVE).stream()
                        .map(GroupMember::getUser).map(User::getId).forEach(recipients::add);
                yield "Giảng viên đã nhận xét tiến độ " + event.label() + " của nhóm " + code;
            }
        };
        recipients.remove(null);
        recipients.remove(event.actorId()); // nobody needs to be told about their own action

        for (UUID recipientId : recipients) {
            if (notificationRepository.existsByEventIdAndRecipientId(event.eventId(), recipientId)) {
                continue;
            }
            notificationRepository.save(Notification.builder()
                    .eventId(event.eventId())
                    .recipient(userRepository.getReferenceById(recipientId))
                    .type(event.type())
                    .message(message)
                    .groupId(group.getId())
                    .entityId(event.entityId())
                    .build());
        }
    }

    private void addSupervisor(StudentGroup group, Set<UUID> recipients) {
        if (group.getSupervisor() != null) {
            recipients.add(group.getSupervisor().getId());
        }
    }
}
