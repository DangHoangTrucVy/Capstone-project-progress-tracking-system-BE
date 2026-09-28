package com.capstone.tracking.notification;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.notification.dto.NotificationResponse;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.notification.email.EmailOutbox;
import com.capstone.tracking.notification.email.EmailOutboxRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a {@link DomainEvent} into in-app notifications for the people who should hear about it, broadcasts them to
 * open notification streams (after commit) and, for {@link DomainEventType#isEmailed() emailed} types, queues an email
 * to the group (To the leader, CC the other members and the supervisor) in the {@link EmailOutbox} — in this same
 * transaction, sent later by the dispatcher, so SMTP never delays the request. Runs after the business transaction
 * committed (in-process) or from the SQS consumer, so it always opens its own transaction. Idempotent: per
 * (event, recipient) for notifications and per event for the email, since SQS delivers at least once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHandler {

    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final NotificationRepository notificationRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final NotificationBroadcaster broadcaster;
    private final EmailOutboxRepository emailOutboxRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(DomainEvent event) {
        Optional<StudentGroup> maybeGroup = studentGroupRepository.findById(event.groupId());
        if (maybeGroup.isEmpty()) {
            log.warn("Dropping {} {}: group {} no longer exists", event.type(), event.eventId(), event.groupId());
            return;
        }
        StudentGroup group = maybeGroup.get();
        String code = group.getGroupCode();
        List<GroupMember> members = groupMemberRepository.findByGroupIdAndStatus(group.getId(), MemberStatus.ACTIVE);

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
                addMembers(members, recipients);
                yield "Giảng viên đã nhận xét tiến độ " + event.label() + " của nhóm " + code;
            }
            case TOPIC_PROPOSAL_SUBMITTED -> {
                addSupervisor(group, recipients);
                yield "Nhóm " + code + " đã nộp danh sách đề tài " + event.label() + " chờ sơ duyệt";
            }
            case TOPIC_FORWARDED_TO_COUNCIL -> {
                addMembers(members, recipients);
                addCouncil(recipients);
                yield "Đề tài \"" + event.label() + "\" của nhóm " + code + " đã được chuyển lên Hội đồng thẩm định";
            }
            case TOPIC_APPROVED -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Hội đồng đã DUYỆT đề tài \"" + event.label() + "\" của nhóm " + code;
            }
            case TOPIC_REJECTED -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Hội đồng KHÔNG DUYỆT đề tài \"" + event.label() + "\" của nhóm " + code;
            }
            case PROPOSAL_ROUND_OPENED -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Cổng đăng ký đề tài " + event.label() + " đã mở cho nhóm " + code;
            }
            case WARNING_FLAG_RAISED -> {
                addMembers(members, recipients);
                yield "Nhóm " + code + " bị gắn cờ cảnh báo: " + event.label();
            }
            case WARNING_FLAG_RESOLVED -> {
                addMembers(members, recipients);
                yield "Cờ cảnh báo \"" + event.label() + "\" của nhóm " + code + " đã được gỡ";
            }
            case REVIEW_SCHEDULED -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Nhóm " + code + " có lịch " + event.label();
            }
            case REVIEW_RESULT -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Kết quả " + event.label() + " của nhóm " + code;
            }
            case DEFENSE_SCHEDULED -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Nhóm " + code + " có lịch " + event.label();
            }
            case DEFENSE_RESULT -> {
                addMembers(members, recipients);
                addSupervisor(group, recipients);
                yield "Kết quả " + event.label() + " của nhóm " + code;
            }
        };
        recipients.remove(null);
        recipients.remove(event.actorId()); // nobody needs to be told about their own action

        List<Notification> created = new ArrayList<>();
        for (UUID recipientId : recipients) {
            if (notificationRepository.existsByEventIdAndRecipientId(event.eventId(), recipientId)) {
                continue;
            }
            created.add(notificationRepository.save(Notification.builder()
                    .eventId(event.eventId())
                    .recipient(userRepository.getReferenceById(recipientId))
                    .type(event.type())
                    .message(truncate(message, 500))
                    .details(event.details())
                    .deadline(event.deadline())
                    .groupId(group.getId())
                    .entityId(event.entityId())
                    .build()));
        }
        if (event.type().isEmailed() && !emailOutboxRepository.existsByEventId(event.eventId())) {
            EmailMessage email = buildEmail(event, group, members, message);
            if (email != null) {
                emailOutboxRepository.save(EmailOutbox.of(event.eventId(), email));
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

    /** Leader in To; other members and the supervisor in CC. Falls back to all members when there is no leader. */
    private EmailMessage buildEmail(DomainEvent event, StudentGroup group, List<GroupMember> members, String message) {
        List<String> to = new ArrayList<>();
        List<String> cc = new ArrayList<>();
        for (GroupMember m : members) {
            (m.isLeader() ? to : cc).add(m.getUser().getEmail());
        }
        if (to.isEmpty()) {
            to.addAll(cc);
            cc.clear();
        }
        if (group.getSupervisor() != null) {
            cc.add(group.getSupervisor().getEmail());
        }
        if (to.isEmpty()) {
            return null;
        }

        String topicTitle = group.getTopic() != null ? group.getTopic().getTitle() : event.label();
        StringBuilder body = new StringBuilder()
                .append("Mã nhóm: ").append(group.getGroupCode()).append('\n')
                .append("Đề tài: ").append(topicTitle == null ? "(chưa có)" : topicTitle).append('\n')
                .append("Kết quả / trạng thái: ").append(message).append('\n');
        if (event.details() != null && !event.details().isBlank()) {
            body.append("Nhận xét / lời nhắc: ").append(event.details()).append('\n');
        }
        if (event.deadline() != null) {
            body.append("Hạn chót: ").append(VN_TIME.format(event.deadline())).append('\n');
        }
        body.append("\nVui lòng đăng nhập hệ thống Capstone Tracking để xem chi tiết.");
        return new EmailMessage(to, cc, "[Capstone] Nhóm " + group.getGroupCode() + " - " + truncate(message, 150),
                body.toString());
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

    private void addMembers(List<GroupMember> members, Set<UUID> recipients) {
        members.stream().map(GroupMember::getUser).map(User::getId).forEach(recipients::add);
    }

    private void addSupervisor(StudentGroup group, Set<UUID> recipients) {
        if (group.getSupervisor() != null) {
            recipients.add(group.getSupervisor().getId());
        }
    }

    private void addCouncil(Set<UUID> recipients) {
        userRepository.findByRole(Role.COUNCIL, Pageable.unpaged()).stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .forEach(recipients::add);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
