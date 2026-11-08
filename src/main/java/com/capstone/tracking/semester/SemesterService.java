package com.capstone.tracking.semester;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The semester (3): create, update, delete an empty one, the Moderator assignment (3.8) and the rules (3.2). The phase
 * and the deadline are handled by {@link PhaseService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SemesterService {

    private static final Pattern CODE = Pattern.compile("^(SP|SU|FA)[0-9]{2}$");

    private final SemesterRepository semesters;
    private final SemesterStudentRepository semesterStudents;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final FormationScopeRepository scopes;
    private final UserRepository users;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final Locks locks;
    private final FormationTiming timing;
    private final PhaseService phases;

    public record SemesterRequest(String code, String name, LocalDate startDate, LocalDate endDate,
                                  @Schema(description = "Opening date of the timeline (display only; the Moderator starts formation)")
                                  Instant formationOpensAt,
                                  @Schema(description = "Common deadline; on creation only. Later use PUT /semesters/{code}/deadline")
                                  Instant deadline) {}

    public record RulesRequest(Integer maxGroupSize, Integer defaultMinRoomSize, Integer maxPendingRequests,
                               @Schema(description = "Lifetime of a request to join a locked room, in minutes (1-20160)")
                               Integer requestTtlMinutes,
                               @Schema(description = "Also give the new default minimum to every room not finalized yet")
                               Boolean applyMinToRooms) {}

    public record Rules(int maxGroupSize, int defaultMinRoomSize, int maxPendingRequests, int requestTtlMinutes,
                        int minDeadlineNoticeMinutes, boolean maxGroupSizeOnlyIncreases) {}

    public record ModeratorRef(UUID id, String fullName, String email) {}

    public record Timeline(Instant formationOpensAt, Instant deadline, Instant formingStartedAt, Instant roomsLockedAt,
                           Instant round1FinalizedAt, Instant round2FinalizedAt, Instant closedAt) {}

    public record SemesterResponse(String code, String name, LocalDate startDate, LocalDate endDate,
                                   SemesterPhase phase, String phaseLabel, boolean beforeDeadline,
                                   Instant formationOpensAt, Instant deadline, Timeline timeline, Rules rules,
                                   ModeratorRef moderator, long studentCount, long roomCount, long groupCount,
                                   long majorCount) {}

    // ------------------------------------------------------------------ semesters

    @Transactional
    public Semester create(SemesterRequest request, User actor) {
        access.requireAdmin(actor);
        String code = request.code() == null ? "" : request.code().trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException("The semester code must look like SP26, SU26 or FA26");
        }
        if (semesters.existsById(code)) {
            throw new ConflictException("Semester " + code + " already exists");
        }
        requireDates(request.startDate(), request.endDate());
        Semester semester = new Semester(code, blank(request.name()) ? defaultName(code) : request.name().trim(),
                request.startDate(), request.endDate());
        semester.setRequestTtlMinutes(timing.defaultRequestTtlMinutes());
        semester.setFormationOpensAt(request.formationOpensAt());
        if (request.deadline() != null) {
            phases.requireNotice(request.deadline(), Instant.now());
            semester.setDeadline(request.deadline());
        }
        requireTimeline(semester);
        return semesters.saveAndFlush(semester);
    }

    /** The Admin edits the name and dates; the Admin or the Moderator the opening date of the timeline. */
    @Transactional
    public Semester update(String code, SemesterRequest request, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        if (access.isAdmin(actor)) {
            if (!blank(request.name())) {
                semester.setName(request.name().trim());
            }
            LocalDate start = request.startDate() == null ? semester.getStartDate() : request.startDate();
            LocalDate end = request.endDate() == null ? semester.getEndDate() : request.endDate();
            requireDates(start, end);
            semester.setStartDate(start);
            semester.setEndDate(end);
        } else if (!blank(request.name()) || request.startDate() != null || request.endDate() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ONLY", "Only an Admin changes the semester's name and dates");
        }
        if (request.formationOpensAt() != null) {
            semester.setFormationOpensAt(request.formationOpensAt());
        }
        if (request.deadline() != null && !request.deadline().equals(semester.getDeadline())) {
            throw new BadRequestException("Change the deadline with PUT /api/v1/semesters/" + code + "/deadline");
        }
        requireTimeline(semester);
        return semester;
    }

    /** A semester holding student data cannot be deleted (3.1). */
    @Transactional
    public void delete(String code, User actor) {
        access.requireAdmin(actor);
        Semester semester = locks.writeSemester(code);
        if (semesterStudents.countBySemesterCode(code) > 0 || groups.countActiveBySemester(code) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "SEMESTER_IN_USE",
                    "This semester has students or rooms and cannot be deleted");
        }
        scopes.deleteAll(scopes.findBySemester(code));
        scopes.flush();
        semesters.delete(semester);
    }

    /** One Moderator per semester, an active Moderator account; null removes the assignment (3.8). */
    @Transactional
    public Semester assignModerator(String code, UUID lecturerId, User actor) {
        access.requireAdmin(actor);
        Semester semester = locks.writeSemester(code);
        UUID before = semester.getModeratorId();
        if (lecturerId != null) {
            User lecturer = users.findById(lecturerId).orElseThrow(() -> ResourceNotFoundException.of("User", lecturerId));
            if (lecturer.getRole() != Role.LECTURER || lecturer.getStatus() != UserStatus.ACTIVE) {
                throw new BadRequestException("The Moderator must be an active Moderator account created by Admin");
            }
        }
        semester.setModeratorId(lecturerId);
        audit.record("Semester", PhaseService.entityId(code), AuditAction.MODERATOR_ASSIGNED, actor, code,
                map("from", before, "to", lecturerId));
        if (lecturerId != null && !lecturerId.equals(before)) {
            notifier.publish(DomainEvent.of(DomainEventType.MODERATOR_ASSIGNED,
                    "Bạn được phân công làm Moderator học kỳ " + code, lecturerId).by(actor.getId()));
        }
        return semester;
    }

    // ------------------------------------------------------------------ rules (3.2)

    public Rules rules(String code, User viewer) {
        Semester semester = get(code);
        if (!mayView(semester, viewer)) {
            throw new AccessDeniedException("This semester is not assigned to you");
        }
        return rules(semester);
    }

    /**
     * The Moderator's rules. The maximum size changes freely while the semester opens and only grows from formation on;
     * it never drops below a room's minimum or current size. The default minimum (1..max) applies to new rooms, and to
     * every room not finalized when {@code applyMinToRooms}, before round 1 only.
     */
    @Transactional
    public Semester updateRules(String code, RulesRequest request, User actor) {
        access.requireModerator(code, actor);
        Semester semester = locks.writeSemester(code);
        if (semester.isClosed()) {
            throw new ApiException(HttpStatus.CONFLICT, "SEMESTER_CLOSED", "Group formation has ended");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        List<StudentGroup> rooms = groups.findActiveBySemester(code);
        if (request.maxGroupSize() != null && request.maxGroupSize() != semester.getMaxGroupSize()) {
            int max = request.maxGroupSize();
            if (max < 1 || max > 20) {
                throw new BadRequestException("The maximum group size must be between 1 and 20");
            }
            if (semester.getPhase() != SemesterPhase.OPENING && max < semester.getMaxGroupSize()) {
                throw new ApiException(HttpStatus.CONFLICT, "MAX_ONLY_INCREASES",
                        "Once formation has started the maximum group size can only increase");
            }
            int largestMin = rooms.stream().filter(g -> !g.isApproved()).mapToInt(StudentGroup::getMinSize).max().orElse(1);
            int largestRoom = largestRoom(rooms);
            int defaultMin = request.defaultMinRoomSize() != null ? request.defaultMinRoomSize() : semester.getDefaultMinRoomSize();
            if (max < Math.max(defaultMin, Math.max(largestMin, largestRoom))) {
                throw new BadRequestException("The maximum group size cannot be below a room's minimum size or a room's "
                        + "current number of members");
            }
            changes.put("maxGroupSize", semester.getMaxGroupSize() + " -> " + max);
            semester.setMaxGroupSize(max);
        }
        boolean applyMin = Boolean.TRUE.equals(request.applyMinToRooms());
        if (request.defaultMinRoomSize() != null
                && (request.defaultMinRoomSize() != semester.getDefaultMinRoomSize() || applyMin)) {
            if (semester.getPhase() != SemesterPhase.OPENING && semester.getPhase() != SemesterPhase.FORMING) {
                throw new ApiException(HttpStatus.CONFLICT, "AFTER_ROUND1",
                        "The minimum room size can only change before round 1 is finalized");
            }
            int min = request.defaultMinRoomSize();
            if (min < 1 || min > semester.getMaxGroupSize()) {
                throw new BadRequestException("The minimum room size must be between 1 and the maximum group size ("
                        + semester.getMaxGroupSize() + ")");
            }
            changes.put("defaultMinRoomSize", semester.getDefaultMinRoomSize() + " -> " + min);
            semester.setDefaultMinRoomSize(min);
            if (applyMin) {
                long updated = rooms.stream().filter(g -> !g.isApproved()).peek(g -> g.setMinSize(min)).count();
                changes.put("roomsUpdated", updated);
            }
        }
        if (request.maxPendingRequests() != null && request.maxPendingRequests() != semester.getMaxPendingRequests()) {
            if (request.maxPendingRequests() < 1 || request.maxPendingRequests() > 20) {
                throw new BadRequestException("Pending requests per student must be between 1 and 20");
            }
            changes.put("maxPendingRequests", semester.getMaxPendingRequests() + " -> " + request.maxPendingRequests());
            semester.setMaxPendingRequests(request.maxPendingRequests());
        }
        if (request.requestTtlMinutes() != null && request.requestTtlMinutes() != semester.getRequestTtlMinutes()) {
            if (request.requestTtlMinutes() < 1 || request.requestTtlMinutes() > 20160) {
                throw new BadRequestException("The request lifetime must be between 1 minute and 14 days (20160 minutes)");
            }
            changes.put("requestTtlMinutes", semester.getRequestTtlMinutes() + " -> " + request.requestTtlMinutes());
            semester.setRequestTtlMinutes(request.requestTtlMinutes());
        }
        if (!changes.isEmpty()) {
            audit.record("Semester", PhaseService.entityId(code), AuditAction.RULES_CHANGED, actor, code, changes);
        }
        return semester;
    }

    private int largestRoom(List<StudentGroup> rooms) {
        if (rooms.isEmpty()) {
            return 0;
        }
        Map<UUID, Integer> sizes = new HashMap<>();
        members.findActiveIn(rooms.stream().map(StudentGroup::getId).toList())
                .forEach(m -> sizes.merge(m.getGroup().getId(), 1, Integer::sum));
        return sizes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    public Rules rules(Semester s) {
        return new Rules(s.getMaxGroupSize(), s.getDefaultMinRoomSize(), s.getMaxPendingRequests(),
                s.getRequestTtlMinutes(), timing.minDeadlineNoticeMinutes(), s.getPhase() != SemesterPhase.OPENING);
    }

    // ------------------------------------------------------------------ reading

    public Semester get(String code) {
        return semesters.findById(code).orElseThrow(() -> ResourceNotFoundException.of("Semester", code));
    }

    public SemesterResponse details(String code, User viewer) {
        Semester semester = get(code);
        if (!mayView(semester, viewer)) {
            throw new AccessDeniedException("This semester is not assigned to you");
        }
        return toResponse(semester);
    }

    private boolean mayView(Semester semester, User viewer) {
        return access.isModerator(semester, viewer) || (viewer.getRole() == Role.STUDENT
                && semesterStudents.findBySemesterCodeAndUserId(semester.getCode(), viewer.getId()).isPresent());
    }

    /**
     * The semester list with code, dates, phase and the numbers of students, rooms and groups (3.1); search by code or
     * name, filter by phase or "coordinated by me", sorted by code, startDate, phase, studentCount or roomCount.
     */
    public List<SemesterResponse> list(String q, SemesterPhase phase, boolean mine, User viewer, String sort, boolean desc) {
        Comparator<SemesterResponse> order = switch (sort == null ? "startDate" : sort) {
            case "code" -> Comparator.comparing(SemesterResponse::code);
            case "phase" -> Comparator.comparing(SemesterResponse::phase);
            case "studentCount" -> Comparator.comparingLong(SemesterResponse::studentCount);
            case "roomCount", "groupCount" -> Comparator.comparingLong(SemesterResponse::roomCount);
            default -> Comparator.comparing(SemesterResponse::startDate);
        };
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return semesters.findAllByOrderByStartDateDesc().stream()
                .filter(s -> mayView(s, viewer))
                .filter(s -> !mine || viewer.getId().equals(s.getModeratorId()))
                .filter(s -> phase == null || s.getPhase() == phase)
                .filter(s -> query.isEmpty() || (s.getCode() + " " + s.getName()).toLowerCase(Locale.ROOT).contains(query))
                .map(this::toResponse)
                .sorted(desc ? order.reversed() : order)
                .toList();
    }

    public SemesterResponse toResponse(Semester s) {
        ModeratorRef moderator = s.getModeratorId() == null ? null : users.findById(s.getModeratorId())
                .map(u -> new ModeratorRef(u.getId(), u.getFullName(), u.getEmail())).orElse(null);
        List<StudentGroup> rooms = groups.findActiveBySemester(s.getCode());
        long coded = rooms.stream().filter(StudentGroup::isApproved).count();
        return new SemesterResponse(s.getCode(), s.getName(), s.getStartDate(), s.getEndDate(), s.getPhase(),
                s.getPhase().label(), s.isBeforeDeadline(Instant.now()), s.getFormationOpensAt(), s.getDeadline(),
                new Timeline(s.getFormationOpensAt(), s.getDeadline(), s.getFormingStartedAt(), s.getRoomsLockedAt(),
                        s.getRound1FinalizedAt(), s.getRound2FinalizedAt(), s.getClosedAt()),
                rules(s), moderator, semesterStudents.countBySemesterCode(s.getCode()), rooms.size(), coded,
                scopes.findBySemester(s.getCode()).size());
    }

    // ------------------------------------------------------------------ helpers

    private static void requireDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BadRequestException("The start and end dates are required");
        }
        if (!end.isAfter(start)) {
            throw new BadRequestException("The semester must end after it starts");
        }
    }

    private static void requireTimeline(Semester s) {
        if (s.getFormationOpensAt() != null && s.getDeadline() != null && !s.getDeadline().isAfter(s.getFormationOpensAt())) {
            throw new BadRequestException("The deadline must come after the opening date");
        }
    }

    private static String defaultName(String code) {
        String season = switch (code.substring(0, 2)) {
            case "SP" -> "Spring";
            case "SU" -> "Summer";
            default -> "Fall";
        };
        return season + " 20" + code.substring(2);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((String) pairs[i], Objects.toString(pairs[i + 1], null));
        }
        return m;
    }
}
