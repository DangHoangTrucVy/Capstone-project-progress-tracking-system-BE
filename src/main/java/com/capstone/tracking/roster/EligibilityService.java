package com.capstone.tracking.roster;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A student's eligibility in a subject (5.1, 5.2): from the training department's file, or changed by the Moderator
 * with a reason. Flagging a student ineligible takes them out of their group of that subject, cancels their open
 * requests, hands the Leader role on (6.5) and dissolves a group left empty. It applies in every phase, including
 * after finalization without reopening. Lifting the flag does not restore the old group.
 */
@Service
@RequiredArgsConstructor
public class EligibilityService {

    private final EnrollmentRepository enrollments;
    private final GroupMemberRepository members;
    private final JoinRequestRepository requests;
    private final GroupCore core;
    private final ScopeRules rules;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final com.capstone.tracking.common.Locks locks;

    public record Change(UUID enrollmentId, Eligibility eligibility, String reason) {}

    /** The Moderator's manual change; a reason is required. */
    @Transactional
    public Enrollment change(UUID enrollmentId, Eligibility eligibility, String reason, User actor) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Enrollment", enrollmentId));
        access.requireModerator(enrollment.getScope().semesterCode(), actor);
        if (eligibility == null) {
            throw new BadRequestException("The eligibility is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required to change a student's eligibility");
        }
        apply(List.of(new Change(enrollmentId, eligibility, reason.trim())), actor);
        return enrollments.findById(enrollmentId).orElseThrow();
    }

    /**
     * Applies several changes at once (an imported file). Everyone flagged is taken out first; only then do the groups
     * they left choose a new Leader or get dissolved (6.5).
     */
    @Transactional
    public int apply(List<Change> changes, User actor) {
        Instant now = Instant.now();
        Map<UUID, StudentGroup> touched = new LinkedHashMap<>();
        List<Enrollment> selected = changes.stream().map(c -> enrollments.findById(c.enrollmentId())
                .orElseThrow(() -> ResourceNotFoundException.of("Enrollment", c.enrollmentId()))).toList();
        selected.stream().map(e -> e.getScope().getId()).distinct().sorted()
                .forEach(id -> locks.read(com.capstone.tracking.subject.FormationScope.class, id));
        selected.stream().map(Enrollment::getId).distinct().sorted().forEach(core::lockEnrollment);
        members.findActiveByEnrollments(selected.stream().map(Enrollment::getId).toList()).stream()
                .map(m -> m.getGroup().getId()).distinct().sorted().forEach(core::lock);
        int changed = 0;
        for (Change change : changes) {
            Enrollment e = core.lockEnrollment(change.enrollmentId());
            access.requireModerator(e.getScope().semesterCode(), actor);
            Eligibility before = e.getEligibility();
            String reason = change.reason() == null || change.reason().isBlank() ? null : change.reason().trim();
            if (before == change.eligibility() && java.util.Objects.equals(reason, e.getEligibilityReason())) {
                continue;
            }
            changed++;
            e.setEligibility(change.eligibility());
            e.setEligibilityReason(reason);
            e.setEligibilityUpdatedBy(actor.getId());
            e.setEligibilityUpdatedAt(now);
            String subject = e.getScope().getSubject().getName();
            if (change.eligibility() == Eligibility.INELIGIBLE) {
                requests.cancelPendingOfEnrollment(e.getId(), JoinRequest.CancelReason.INELIGIBLE, now);
                GroupMember member = members.findActiveByEnrollment(e.getId()).orElse(null);
                if (member != null) {
                    StudentGroup group = core.lock(member.getGroup().getId());
                    core.remove(member, GroupMember.LeftReason.INELIGIBLE, NoGroupReason.INELIGIBLE, now, false);
                    touched.put(group.getId(), group);
                    notifier.publish(DomainEvent.of(DomainEventType.REMOVED_INELIGIBLE,
                            "Bạn được xác định không đủ điều kiện môn " + subject + " và đã bị loại khỏi nhóm "
                                    + group.getName(), e.getUser().getId())
                            .about(group.getId(), e.getId()).withDetails(reason, null).by(actor.getId()));
                    notifier.publish(DomainEvent.of(DomainEventType.MEMBER_LEFT,
                            e.getUser().getFullName() + " không đủ điều kiện và đã rời nhóm " + group.getName(),
                            core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId()).toList())
                            .about(group.getId(), null).by(actor.getId()));
                } else {
                    e.setNoGroupReason(NoGroupReason.INELIGIBLE);
                }
            } else if (before == Eligibility.INELIGIBLE && e.getNoGroupReason() == NoGroupReason.INELIGIBLE) {
                e.setNoGroupReason(null);
            }
            notifier.publish(DomainEvent.of(DomainEventType.ELIGIBILITY_CHANGED,
                    "Điều kiện tham gia môn " + subject + " của bạn: " + label(change.eligibility()),
                    e.getUser().getId()).about(null, e.getId()).withDetails(reason, null).by(actor.getId()));
            audit.record("Enrollment", e.getId(), AuditAction.ELIGIBILITY_CHANGED, actor, e.getScope().semesterCode(),
                    Map.of("student", String.valueOf(e.getUser().getStudentCode()), "from", before.name(),
                            "to", change.eligibility().name(), "reason", reason == null ? "" : reason));
        }
        touched.values().forEach(g -> core.settle(g, now));
        return changed;
    }

    private static String label(Eligibility eligibility) {
        return switch (eligibility) {
            case ELIGIBLE -> "Đủ điều kiện";
            case INELIGIBLE -> "Không đủ điều kiện";
            case UNDECIDED -> "Chưa kết luận";
        };
    }
}
