package com.capstone.tracking.subject;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.subject.dto.FinalizeCheck;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.JoinRequestRepository;
import com.capstone.tracking.team.LeaderTransferRepository;
import com.capstone.tracking.team.LeaveRequestRepository;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The life of a formation scope (3.4): automatic opening and locking, the Moderator's deadlines and extensions (3.5),
 * finalizing the list (7.2) and reopening it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeService {

    private final FormationScopeRepository scopes;
    private final SemesterRepository semesters;
    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final JoinRequestRepository joinRequests;
    private final LeaveRequestRepository leaveRequests;
    private final LeaderTransferRepository transfers;
    private final GroupCore core;
    private final ScopeRules rules;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final FormationTiming timing;

    public FormationScope get(UUID scopeId) {
        return scopes.findById(scopeId).orElseThrow(() -> ResourceNotFoundException.of("Scope", scopeId));
    }

    // ------------------------------------------------------------------ deadlines (3.5)

    /**
     * The Moderator's own deadline for the scope, earlier or later than the common one; null goes back to the common
     * deadline. Before the deadline only; after it the scope is extended instead.
     */
    @Transactional
    public FormationScope setDeadline(UUID scopeId, Instant deadline, User actor) {
        FormationScope scope = lock(scopeId);
        access.requireModerator(scope.semesterCode(), actor);
        if (scope.getPhase() != ScopePhase.PREPARING && scope.getPhase() != ScopePhase.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_OPEN",
                    "The deadline has passed; extend the scope instead");
        }
        Instant now = Instant.now();
        if (scope.getPhase() == ScopePhase.OPEN && !rules.isOpen(scope, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_OPEN",
                    "The deadline has passed; extend the scope instead");
        }
        Instant before = rules.deadline(scope);
        scope.setDeadlineOverride(deadline);
        Instant effective = rules.deadline(scope);
        requireNotice(effective, now);
        scope.setOpenBlockedNotifiedAt(null);
        audit.record("FormationScope", scope.getId(), AuditAction.DEADLINE_CHANGED, actor, scope.semesterCode(),
                details("from", before, "to", effective, "own", deadline != null));
        notifyStudents(scope, DomainEventType.DEADLINE_CHANGED,
                "Deadline ghép nhóm môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName()
                        + ") đổi thành " + VnTime.format(effective), effective, actor);
        if (scope.getPhase() == ScopePhase.PREPARING) {
            tryOpen(scope, now);
        }
        return scope;
    }

    /** After the deadline: reopen formation with a new deadline, without approval (3.4). */
    @Transactional
    public FormationScope extend(UUID scopeId, Instant deadline, User actor) {
        FormationScope scope = lock(scopeId);
        access.requireModerator(scope.semesterCode(), actor);
        if (scope.getPhase() != ScopePhase.LOCKED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_LOCKED",
                    "Only a scope past its deadline (and not finalized) can be extended");
        }
        if (deadline == null) {
            throw new BadRequestException("The new deadline is required");
        }
        requireNotice(deadline, Instant.now());
        scope.setDeadlineOverride(deadline);
        scope.setPhase(ScopePhase.OPEN);
        audit.record("FormationScope", scope.getId(), AuditAction.SCOPE_EXTENDED, actor, scope.semesterCode(),
                details("deadline", deadline));
        notifyStudents(scope, DomainEventType.SCOPE_EXTENDED,
                "Ghép nhóm môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName()
                        + ") được gia hạn tới " + VnTime.format(deadline), deadline, actor);
        return scope;
    }

    /** Changing the common deadline moves every scope that follows it and has not passed it yet (3.5). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void commonDeadlineChanged(Semester semester, Instant before, User actor) {
        Instant now = Instant.now();
        for (FormationScope scope : scopes.findBySemester(semester.getCode())) {
            if (scope.getDeadlineOverride() != null
                    || (scope.getPhase() != ScopePhase.PREPARING && scope.getPhase() != ScopePhase.OPEN)) {
                continue;
            }
            if (scope.getPhase() == ScopePhase.OPEN && before != null && !now.isBefore(before)) {
                continue; // already past the old deadline: the scheduler locks it, the Moderator extends it
            }
            scope.setOpenBlockedNotifiedAt(null);
            notifyStudents(scope, DomainEventType.DEADLINE_CHANGED,
                    "Deadline ghép nhóm môn " + scope.getSubject().getName() + " đổi thành "
                            + VnTime.format(semester.getDefaultDeadline()), semester.getDefaultDeadline(), actor);
        }
    }

    public void requireNotice(Instant deadline, Instant now) {
        if (deadline == null) {
            throw new BadRequestException("The scope has no deadline: set the common deadline or its own deadline");
        }
        if (deadline.isBefore(now.plus(timing.minimumDeadlineNotice()))) {
            throw new BadRequestException("A new deadline must be at least "
                    + timing.minDeadlineNoticeMinutes() + " minutes from now");
        }
    }

    // ------------------------------------------------------------------ finalizing (7.2)

    public FinalizeCheck check(UUID scopeId) {
        return check(get(scopeId));
    }

    FinalizeCheck check(FormationScope scope) {
        int min = scope.getSubject().getMinGroupSize();
        List<StudentGroup> active = groups.findActiveByScope(scope.getId());
        Map<UUID, Long> sizes = sizes(active);
        List<FinalizeCheck.GroupRef> small = active.stream()
                .filter(g -> sizes.getOrDefault(g.getId(), 0L) < min)
                .map(g -> new FinalizeCheck.GroupRef(g.getId(), g.getCode(), g.getName(), sizes.getOrDefault(g.getId(), 0L)))
                .toList();
        long openLeaves = leaveRequests.countOpenInScope(scope.getId());
        List<Enrollment> all = enrollments.findByScopeId(scope.getId());
        java.util.Set<UUID> grouped = members.findActiveByEnrollments(all.stream().map(Enrollment::getId).toList())
                .stream().map(m -> m.getEnrollment().getId()).collect(Collectors.toSet());
        long ungroupedEligible = all.stream()
                .filter(e -> e.getEligibility() != Eligibility.INELIGIBLE && !grouped.contains(e.getId()))
                .count();
        List<String> blockers = new ArrayList<>();
        if (scope.getPhase() != ScopePhase.LOCKED) {
            blockers.add("The scope must be past its deadline (phase LOCKED); it is " + scope.getPhase());
        }
        if (!small.isEmpty()) {
            blockers.add(small.size() + " group(s) have fewer than " + min + " members");
        }
        if (openLeaves > 0) {
            blockers.add(openLeaves + " leave request(s) or complaint(s) are not handled yet");
        }
        List<String> warnings = new ArrayList<>();
        if (ungroupedEligible > 0) {
            warnings.add(ungroupedEligible + " eligible or undecided student(s) have no group");
        }
        return new FinalizeCheck(blockers.isEmpty(), blockers, warnings, small, openLeaves, ungroupedEligible);
    }

    @Transactional
    public FormationScope finalizeScope(UUID scopeId, User actor) {
        FormationScope scope = lock(scopeId);
        access.requireModerator(scope.semesterCode(), actor);
        FinalizeCheck check = check(scope);
        if (!check.canFinalize()) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_FINALIZE", String.join("; ", check.blockers()));
        }
        Instant now = Instant.now();
        int min = scope.getSubject().getMinGroupSize();
        int max = scope.getSubject().getMaxGroupSize();
        List<StudentGroup> active = groups.findActiveByScope(scope.getId());
        Map<UUID, Long> sizes = sizes(active);
        java.util.Set<UUID> handedOver = new java.util.HashSet<>();
        for (StudentGroup group : active) {
            long size = sizes.getOrDefault(group.getId(), 0L);
            boolean finalized = size >= min && size <= max;
            group.setFinalized(finalized);
            group.setFinalizedAt(finalized ? now : null);
            if (finalized) handedOver.add(group.getId());
        }
        transfers.cancelPendingOfScope(scope.getId(), now);
        scope.setPhase(ScopePhase.FINALIZED);
        scope.setFinalizedAt(now);
        scope.setFinalizedBy(actor.getId());
        scope.setLastChangeAfterFinalize(null);
        scope.setLastChangeAfterFinalizeAt(null);
        audit.record("FormationScope", scope.getId(), AuditAction.SCOPE_FINALIZED, actor, scope.semesterCode(),
                details("finalizedGroups", handedOver.size(), "warnings", check.warnings()));
        notifyStudents(scope, DomainEventType.SCOPE_FINALIZED,
                "Danh sách nhóm môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName()
                        + ") đã được chốt", null, actor);
        return scope;
    }

    /** Back to "locked by deadline", with a reason; the finalized flags are lifted and the time kept (3.4). */
    @Transactional
    public FormationScope reopen(UUID scopeId, String reason, User actor) {
        FormationScope scope = lock(scopeId);
        access.requireModerator(scope.semesterCode(), actor);
        if (scope.getPhase() != ScopePhase.FINALIZED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_FINALIZED", "Only a finalized list can be reopened");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required to reopen the list");
        }
        Instant now = Instant.now();
        for (StudentGroup group : groups.findActiveByScope(scope.getId())) {
            group.setFinalized(false);
            group.setFinalizedAt(null);
        }
        scope.setPreviousFinalizedAt(scope.getFinalizedAt());
        scope.setFinalizedAt(null);
        scope.setFinalizedBy(null);
        scope.setReopenedAt(now);
        scope.setReopenedBy(actor.getId());
        scope.setReopenReason(reason.trim());
        scope.setPhase(ScopePhase.LOCKED);
        audit.record("FormationScope", scope.getId(), AuditAction.SCOPE_REOPENED, actor, scope.semesterCode(),
                details("reason", reason.trim(), "previousFinalizedAt", scope.getPreviousFinalizedAt()));
        notifier.publish(DomainEvent.of(DomainEventType.SCOPE_REOPENED,
                "Danh sách môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName()
                        + ") đã được mở lại", recipientsForStaff(scope)).about(null, scope.getId())
                .withDetails(reason.trim(), null).by(actor.getId()));
        return scope;
    }

    // ------------------------------------------------------------------ automatic transitions (3.4)

    /** One scope's automatic step: open on the common opening date, lock at the deadline. Own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tick(UUID scopeId) {
        FormationScope scope = lock(scopeId);
        Instant now = Instant.now();
        if (scope.getPhase() == ScopePhase.PREPARING) {
            tryOpen(scope, now);
        } else if (scope.getPhase() == ScopePhase.OPEN) {
            Instant deadline = rules.deadline(scope);
            if (deadline != null && !now.isBefore(deadline)) {
                lockAtDeadline(scope, now);
            }
        }
    }

    /** Open as soon as the last prerequisite is supplied, without waiting for the periodic scheduler (3.4). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void activatePreparing(String semesterCode) {
        for (FormationScope scope : scopes.findBySemester(semesterCode)) {
            if (scope.getPhase() == ScopePhase.PREPARING) {
                FormationScope current = lock(scope.getId());
                if (current.getPhase() == ScopePhase.PREPARING) {
                    tryOpen(current, Instant.now());
                }
            }
        }
    }

    /** Writes down Apply/Invite requests past their lifetime (reads already treat them as expired). */
    @Transactional
    public int expireRequests() {
        return joinRequests.expireDue(Instant.now());
    }

    private void tryOpen(FormationScope scope, Instant now) {
        Semester semester = semesters.findById(scope.semesterCode()).orElseThrow();
        if (semester.getFormationOpensAt() == null || now.isBefore(semester.getFormationOpensAt())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (semester.getModeratorId() == null) {
            missing.add("chưa có Moderator");
        }
        if (enrollments.countByScopeId(scope.getId()) == 0) {
            missing.add("chưa có sinh viên");
        }
        Instant deadline = rules.deadline(scope);
        if (deadline == null) {
            missing.add("chưa có deadline");
        } else if (!now.isBefore(deadline)) {
            missing.add("deadline đã qua, cần đặt deadline mới");
        }
        if (!missing.isEmpty()) {
            if (scope.getOpenBlockedNotifiedAt() == null
                    || scope.getOpenBlockedNotifiedAt().isBefore(now.minus(Duration.ofHours(24)))) {
                scope.setOpenBlockedNotifiedAt(now);
                notifier.publish(DomainEvent.of(DomainEventType.SCOPE_CANNOT_OPEN,
                        "Môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName()
                                + ") chưa mở ghép nhóm được: " + String.join(", ", missing),
                        notifier.coordinators(semester.getCode())).about(null, scope.getId()));
            }
            return;
        }
        scope.setPhase(ScopePhase.OPEN);
        scope.setOpenedAt(now);
        scope.setOpenBlockedNotifiedAt(null);
        audit.record("FormationScope", scope.getId(), AuditAction.SCOPE_OPENED, null, scope.semesterCode(),
                details("deadline", deadline));
        notifyStudents(scope, DomainEventType.SCOPE_OPENED,
                "Đã mở ghép nhóm môn " + scope.getSubject().getName() + ", deadline " + VnTime.format(deadline),
                deadline, null);
    }

    /**
     * The deadline (3.4, 7.1): open requests are cancelled, leave requests waiting for the Leader become complaints,
     * groups below the minimum size are dissolved (once, now) and the Moderator is asked to arrange the rest.
     */
    private void lockAtDeadline(FormationScope scope, Instant now) {
        scope.setPhase(ScopePhase.LOCKED);
        scope.setLockedAt(now);
        int cancelled = joinRequests.cancelPendingOfScope(scope.getId(), now);
        int complaints = leaveRequests.escalatePendingOfScope(scope.getId());
        transfers.cancelPendingLeavesOfScope(scope.getId(), now);
        int min = scope.getSubject().getMinGroupSize();
        List<StudentGroup> active = groups.findActiveByScope(scope.getId());
        Map<UUID, Long> sizes = sizes(active);
        int dissolved = 0;
        for (StudentGroup g : active) {
            if (sizes.getOrDefault(g.getId(), 0L) < min) {
                core.dissolve(locks.write(StudentGroup.class, g.getId()), StudentGroup.DissolveReason.TOO_SMALL_AT_DEADLINE,
                        null, now);
                dissolved++;
            }
        }
        long ungrouped = enrollments.findWithoutGroup(scope.getId()).size();
        audit.record("FormationScope", scope.getId(), AuditAction.SCOPE_LOCKED, null, scope.semesterCode(),
                details("cancelledRequests", cancelled, "complaints", complaints, "dissolvedGroups", dissolved,
                        "studentsWithoutGroup", ungrouped));
        notifyStudents(scope, DomainEventType.SCOPE_LOCKED,
                "Đã tới deadline ghép nhóm môn " + scope.getSubject().getName() + " (ngành "
                        + scope.getMajor().getName() + "). Moderator sẽ sắp xếp các bạn chưa có nhóm.", null, null);
        notifier.publish(DomainEvent.of(DomainEventType.COORDINATION_NEEDED,
                "Môn " + scope.getSubject().getName() + " (ngành " + scope.getMajor().getName() + ") đã khóa theo deadline: "
                        + dissolved + " nhóm dưới " + min + " người bị giải tán, " + ungrouped
                        + " sinh viên chưa có nhóm, " + complaints + " khiếu nại cần xử lý",
                notifier.coordinators(scope.semesterCode())).about(null, scope.getId()));
        log.info("Scope {} locked at its deadline: {} requests cancelled, {} groups dissolved", scope.getId(), cancelled,
                dissolved);
    }

    // ------------------------------------------------------------------ helpers

    private FormationScope lock(UUID scopeId) {
        return locks.write(FormationScope.class, scopeId);
    }

    private Map<UUID, Long> sizes(List<StudentGroup> active) {
        Map<UUID, Long> sizes = new HashMap<>();
        if (active.isEmpty()) {
            return sizes;
        }
        for (GroupMember m : members.findActiveIn(active.stream().map(StudentGroup::getId).toList())) {
            sizes.merge(m.getGroup().getId(), 1L, Long::sum);
        }
        return sizes;
    }

    private void notifyStudents(FormationScope scope, DomainEventType type, String message, Instant deadline, User actor) {
        List<UUID> students = enrollments.findByScopeId(scope.getId()).stream().map(e -> e.getUser().getId()).toList();
        notifier.publish(DomainEvent.of(type, message, students).about(null, scope.getId())
                .withDetails(null, deadline).by(actor == null ? null : actor.getId()));
    }

    private List<UUID> recipientsForStaff(FormationScope scope) {
        List<UUID> ids = new ArrayList<>(notifier.coordinators(scope.semesterCode()));
        ids.addAll(notifier.admins());
        return ids;
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1] == null ? null : pairs[i + 1].toString());
        }
        return map;
    }
}
