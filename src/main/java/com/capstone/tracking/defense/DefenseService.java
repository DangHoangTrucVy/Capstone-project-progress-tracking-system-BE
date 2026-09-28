package com.capstone.tracking.defense;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.defense.dto.DefenseResultRequest;
import com.capstone.tracking.defense.dto.DefenseScheduleRequest;
import com.capstone.tracking.defense.dto.DefenseSessionResponse;
import com.capstone.tracking.defense.dto.RollingScheduleRequest;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.review.ClosedCouncilOutcome;
import com.capstone.tracking.review.PanelSelection;
import com.capstone.tracking.review.ReviewService;
import com.capstone.tracking.review.ReviewSession;
import com.capstone.tracking.review.ReviewSessionRepository;
import com.capstone.tracking.scheduling.ScheduleGuard;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
/**
 * Giai đoạn 6 — final defenses. Who may defend when is decided by the closed council (Review 3):
 * attempt 1 needs READY_FOR_DEFENSE_1, or REVISE_BEFORE_DEFENSE_1 with the revision confirmed; attempt 2 is for
 * groups deferred by the council, whose revision missed its deadline, or who failed attempt 1. Defenses roll
 * ("cuốn chiếu"): a room and a lecturer hold one defense at a time, and at most {@code app.defense.max-parallel}
 * defenses run simultaneously across the campus.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefenseService {


    private final DefenseSessionRepository sessionRepository;
    private final StudentGroupService studentGroupService;
    private final ReviewService reviewService;
    private final UserService userService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final ScheduleGuard scheduleGuard;
    private final ReviewSessionRepository reviewSessions;

    @Value("${app.defense.max-parallel:5}")
    private int maxParallel;

    @Transactional
    public DefenseSessionResponse schedule(DefenseScheduleRequest request, User actingUser) {
        scheduleGuard.acquire();
        StudentGroup group = studentGroupService.getById(request.groupId());
        PanelSelection committee = PanelSelection.resolve(userService, request.committeeIds(), request.chairId(), null, true);
        return DefenseSessionResponse.from(create(group, request.attempt(), request.scheduledAt(),
                request.durationMinutes(), request.room(), committee, actingUser));
    }

    /** Bước 6.1: schedule a list of groups back to back in one room with one committee. All or nothing. */
    @Transactional
    public List<DefenseSessionResponse> scheduleRolling(RollingScheduleRequest request, User actingUser) {
        scheduleGuard.acquire();
        if (new HashSet<>(request.groupIds()).size() != request.groupIds().size()) {
            throw new BadRequestException("A group is listed twice");
        }
        PanelSelection committee = PanelSelection.resolve(userService, request.committeeIds(), request.chairId(), null, true);
        Duration step = Duration.ofMinutes(request.durationMinutes() + request.breakMinutes());
        List<DefenseSessionResponse> created = new ArrayList<>();
        Instant start = request.firstStartTime();
        for (UUID groupId : request.groupIds()) {
            StudentGroup group = studentGroupService.getById(groupId);
            created.add(DefenseSessionResponse.from(create(group, request.attempt(), start, request.durationMinutes(),
                    request.room(), committee, actingUser)));
            start = start.plus(step);
        }
        return created;
    }

    @Transactional
    public DefenseSessionResponse recordResult(UUID id, DefenseResultRequest request, User actingUser) {
        scheduleGuard.acquire();
        DefenseSession session = find(id);
        boolean chair = session.chair().map(m -> m.getMember().getId().equals(actingUser.getId())).orElse(false);
        if (!chair && actingUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only the committee chair records the defense result");
        }
        if (session.getStatus() != DefenseStatus.SCHEDULED) {
            throw new ConflictException("The result of this defense is already recorded");
        }
        boolean passed = request.passed();
        session.setStatus(passed ? DefenseStatus.PASSED : DefenseStatus.FAILED);
        session.setScore(request.score());
        session.setFeedback(request.feedback());
        session.setGradedBy(actingUser);
        session.setGradedAt(Instant.now());

        StudentGroup group = session.getGroup();
        String outcome;
        if (passed) {
            group.setStatus(GroupStatus.COMPLETED);
            outcome = "ĐẠT — hoàn thành đồ án";
        } else if (session.getAttempt() == 1) {
            outcome = "CHƯA ĐẠT — chuyển sang Bảo vệ lần 2";
        } else {
            group.setStatus(GroupStatus.FAILED);
            outcome = "KHÔNG ĐẠT — Fail đồ án";
        }

        auditService.record("DefenseSession", session.getId(), passed ? AuditAction.APPROVE : AuditAction.REJECT, actingUser,
                Map.of("attempt", session.getAttempt(), "score", request.score()));
        events.publishEvent(DomainEvent.of(DomainEventType.DEFENSE_RESULT, group.getId(), session.getId(),
                        actingUser.getId(), "Bảo vệ lần " + session.getAttempt() + ": " + outcome + " (điểm " + request.score() + ")")
                .withDetails(request.feedback(), null));
        return DefenseSessionResponse.from(session);
    }

    public List<DefenseSessionResponse> listByGroup(UUID groupId, User actingUser) {
        studentGroupService.getById(groupId);
        studentGroupService.requireCanView(groupId, actingUser);
        return sessionRepository.findByGroupIdOrderByAttemptAsc(groupId).stream().map(DefenseSessionResponse::from).toList();
    }

    public List<DefenseSessionResponse> listByAttempt(int attempt, String semester) {
        List<DefenseSession> sessions = StringUtils.hasText(semester)
                ? sessionRepository.findByAttemptAndGroup_SemesterOrderByScheduledAtAsc(attempt, semester)
                : sessionRepository.findByAttemptOrderByScheduledAtAsc(attempt);
        return sessions.stream().map(DefenseSessionResponse::from).toList();
    }

    public List<DefenseSessionResponse> listMine(User actingUser) {
        return sessionRepository.findByCommitteeMember(actingUser.getId()).stream().map(DefenseSessionResponse::from).toList();
    }

    public DefenseSessionResponse getById(UUID id, User actingUser) {
        DefenseSession session = find(id);
        studentGroupService.requireCanView(session.getGroup().getId(), actingUser);
        return DefenseSessionResponse.from(session);
    }

    private DefenseSession create(StudentGroup group, int attempt, Instant scheduledAt, int durationMinutes, String room,
                                  PanelSelection committee, User actingUser) {
        requireEligible(group, attempt);
        if (sessionRepository.findByGroupIdAndAttempt(group.getId(), attempt).isPresent()) {
            throw new ConflictException("Group " + group.getGroupCode() + " already has defense attempt " + attempt + " scheduled");
        }
        Instant end = scheduledAt.plus(Duration.ofMinutes(durationMinutes));
        List<DefenseSession> overlapping = sessionRepository
                .findByScheduledAtLessThan(end).stream()
                .filter(s -> s.endsAt().isAfter(scheduledAt))
                .toList();
        Set<UUID> committeeIds = new HashSet<>(committee.ids());
        for (DefenseSession other : overlapping) {
            if (other.getRoom().equalsIgnoreCase(room.trim())) {
                throw new ConflictException("Room " + room + " is taken by group " + other.getGroup().getGroupCode()
                        + " at " + VnTime.format(other.getScheduledAt()));
            }
            if (other.getCommittee().stream().anyMatch(m -> committeeIds.contains(m.getMember().getId()))) {
                throw new ConflictException("A committee member already grades group " + other.getGroup().getGroupCode()
                        + " at " + VnTime.format(other.getScheduledAt()));
            }
        }
        // Count the peak at each interval boundary, not the total number intersecting a long new session.
        List<Instant> starts = new ArrayList<>();
        starts.add(scheduledAt);
        overlapping.stream().map(DefenseSession::getScheduledAt).filter(t -> !t.isBefore(scheduledAt)).forEach(starts::add);
        for (Instant at : starts) {
            long simultaneous = overlapping.stream()
                    .filter(s -> !s.getScheduledAt().isAfter(at) && s.endsAt().isAfter(at)).count();
            if (simultaneous >= maxParallel) {
                throw new ConflictException("At most " + maxParallel + " defenses may run at the same time; pick another time");
            }
        }
        for (var other : reviewSessions.findByScheduledAtLessThan(end)) {
            if (other.endsAt().isAfter(scheduledAt)
                    && (other.getLocation().equalsIgnoreCase(room.trim())
                        || other.getGroup().getId().equals(group.getId())
                        || other.getPanel().stream().anyMatch(m -> committeeIds.contains(m.getReviewer().getId())))) {
                throw new ConflictException("Room, group or committee member already has a review at this time");
            }
        }

        DefenseSession session = DefenseSession.builder()
                .group(group)
                .attempt(attempt)
                .scheduledAt(scheduledAt)
                .durationMinutes(durationMinutes)
                .room(room.trim())
                .status(DefenseStatus.SCHEDULED)
                .build();
        for (User member : committee.members()) {
            session.getCommittee().add(DefenseCommitteeMember.builder()
                    .session(session).member(member).chair(committee.isChair(member)).build());
        }
        session = sessionRepository.save(session);

        auditService.record("DefenseSession", session.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", group.getId(), "attempt", attempt, "room", room));
        events.publishEvent(DomainEvent.of(DomainEventType.DEFENSE_SCHEDULED, group.getId(), session.getId(),
                        actingUser.getId(), "Bảo vệ lần " + attempt + " lúc " + VnTime.format(scheduledAt) + ", phòng " + room)
                .withDetails("Hội đồng chấm: " + committee.describe(), scheduledAt));
        return session;
    }

    private void requireEligible(StudentGroup group, int attempt) {
        if (group.getStatus() == GroupStatus.COMPLETED || group.getStatus() == GroupStatus.FAILED) {
            throw new ConflictException("Group " + group.getGroupCode() + " has already finished (" + group.getStatus() + ")");
        }
        ReviewSession council = reviewService.closedCouncilOf(group.getId())
                .filter(r -> r.getOutcome() != null)
                .orElseThrow(() -> new BadRequestException("Group " + group.getGroupCode()
                        + " has no closed council (Review 3) result yet"));
        if (attempt == 1) {
            if (!council.clearsDefense1()) {
                throw new BadRequestException("Group " + group.getGroupCode() + " is not cleared for Defense 1 ("
                        + council.getOutcome().label() + (council.getOutcome() == ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1
                        ? ", revision not confirmed yet" : "") + ")");
            }
            return;
        }
        boolean failedFirst = sessionRepository.findByGroupIdAndAttempt(group.getId(), 1)
                .map(d -> d.getStatus() == DefenseStatus.FAILED)
                .orElse(false);
        if (!failedFirst && !council.sendsToDefense2(Instant.now())) {
            throw new BadRequestException("Group " + group.getGroupCode()
                    + " goes to Defense 2 only after failing Defense 1 or being deferred by the closed council");
        }
    }

    private DefenseSession find(UUID id) {
        return sessionRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("DefenseSession", id));
    }
}
