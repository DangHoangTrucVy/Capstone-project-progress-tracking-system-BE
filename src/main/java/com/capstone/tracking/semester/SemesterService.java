package com.capstone.tracking.semester;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.subject.ScopeService;
import com.capstone.tracking.subject.SubjectRepository;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The semester list (3.1): create, update, archive, the common timeline and the Moderator assignment (3.6). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SemesterService {

    private static final Pattern CODE = Pattern.compile("^(SP|SU|FA)[0-9]{2}$");

    private final SemesterRepository semesters;
    private final SemesterStudentRepository semesterStudents;
    private final SubjectRepository subjects;
    private final com.capstone.tracking.subject.SubjectService subjectService;
    private final StudentGroupRepository groups;
    private final UserRepository users;
    private final ScopeService scopes;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;

    public record SemesterRequest(String code, String name, LocalDate startDate, LocalDate endDate,
                                  Instant formationOpensAt, Instant defaultDeadline) {}

    public record ModeratorRef(UUID id, String fullName, String email) {}

    public record SemesterResponse(String code, String name, LocalDate startDate, LocalDate endDate,
                                   Instant formationOpensAt, Instant defaultDeadline,
                                   ModeratorRef moderator, long studentCount, long groupCount, long subjectCount) {}

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
        semester.setFormationOpensAt(request.formationOpensAt());
        if (request.defaultDeadline() != null) {
            scopes.requireNotice(request.defaultDeadline(), Instant.now());
            semester.setDefaultDeadline(request.defaultDeadline());
        }
        requireTimeline(semester);
        semester = semesters.saveAndFlush(semester);
        subjectService.create(code, new com.capstone.tracking.subject.SubjectService.SubjectRequest(
                com.capstone.tracking.subject.Subject.CAPSTONE_CODE,
                com.capstone.tracking.subject.Subject.CAPSTONE_NAME,
                com.capstone.tracking.subject.Subject.DEFAULT_TARGET_TERM, null), actor);
        return semester;
    }

    /**
     * The Admin edits everything; the Moderator edits the common timeline (opening date and deadline). A new common
     * deadline must satisfy the configured minimum notice and moves scopes that follow it (3.5).
     */
    @Transactional
    public Semester update(String code, SemesterRequest request, User actor) {
        Semester semester = lock(code);
        access.requireModerator(semester, actor);
        boolean admin = access.isAdmin(actor);
        if (admin) {
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
        Instant before = semester.getDefaultDeadline();
        if (request.defaultDeadline() != null && !request.defaultDeadline().equals(before)) {
            scopes.requireNotice(request.defaultDeadline(), Instant.now());
            semester.setDefaultDeadline(request.defaultDeadline());
            requireTimeline(semester);
            audit.record("Semester", entityId(semester.getCode()),
                    AuditAction.DEADLINE_CHANGED, actor, semester.getCode(),
                    map("commonDeadlineFrom", before, "commonDeadlineTo", semester.getDefaultDeadline()));
            scopes.commonDeadlineChanged(semester, before, actor);
        }
        requireTimeline(semester);
        scopes.activatePreparing(code);
        return semester;
    }

    /** A semester holding student data cannot be deleted (3.1). */
    @Transactional
    public void delete(String code, User actor) {
        access.requireAdmin(actor);
        Semester semester = lock(code);
        if (semesterStudents.countBySemesterCode(code) > 0 || groups.countActiveBySemester(code) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "SEMESTER_IN_USE",
                    "This semester has students and cannot be deleted");
        }
        subjects.deleteAll(subjects.findBySemesterCodeOrderByCodeAsc(code));
        subjects.flush();
        semesters.delete(semester);
    }

    /** One Moderator per semester, chosen from the imported lecturers; null removes the assignment (3.6). */
    @Transactional
    public Semester assignModerator(String code, UUID lecturerId, User actor) {
        access.requireAdmin(actor);
        Semester semester = lock(code);
        UUID before = semester.getModeratorId();
        if (lecturerId != null) {
            User lecturer = users.findById(lecturerId).orElseThrow(() -> ResourceNotFoundException.of("User", lecturerId));
            if (lecturer.getRole() != Role.LECTURER || lecturer.getStatus() != UserStatus.ACTIVE) {
                throw new BadRequestException("The Moderator must be an active Moderator account created by Admin");
            }
        }
        semester.setModeratorId(lecturerId);
        audit.record("Semester", entityId(code), AuditAction.MODERATOR_ASSIGNED, actor, code,
                map("from", before, "to", lecturerId));
        if (lecturerId != null && !lecturerId.equals(before)) {
            notifier.publish(DomainEvent.of(DomainEventType.MODERATOR_ASSIGNED,
                    "Bạn được phân công làm Moderator học kỳ " + code, lecturerId).by(actor.getId()));
        }
        scopes.activatePreparing(code);
        return semester;
    }

    public Semester get(String code) {
        return semesters.findById(code).orElseThrow(() -> ResourceNotFoundException.of("Semester", code));
    }

    public SemesterResponse details(String code, User viewer) {
        Semester semester = get(code);
        if (!mayView(semester, viewer)) throw new org.springframework.security.access.AccessDeniedException("This semester is not assigned to you");
        return toResponse(semester);
    }

    private boolean mayView(Semester semester, User viewer) {
        return access.isModerator(semester, viewer) || (viewer.getRole() == Role.STUDENT
                && semesterStudents.findBySemesterCodeAndUserId(semester.getCode(), viewer.getId()).isPresent());
    }

    /**
     * The semester list with code, dates, status and the numbers of students and groups (3.1); filtered by status or
     * by "coordinated by me", sorted by code, startDate, status, studentCount or groupCount.
     */
    public List<SemesterResponse> list(String q, boolean mine, User viewer, String sort, boolean desc) {
        Instant now = Instant.now();
        Comparator<SemesterResponse> order = switch (sort == null ? "startDate" : sort) {
            case "code" -> Comparator.comparing(SemesterResponse::code);
            case "studentCount" -> Comparator.comparingLong(SemesterResponse::studentCount);
            case "groupCount" -> Comparator.comparingLong(SemesterResponse::groupCount);
            default -> Comparator.comparing(SemesterResponse::startDate);
        };
        return semesters.findAllByOrderByStartDateDesc().stream()
                .filter(s -> mayView(s, viewer))
                .filter(s -> !mine || viewer.getId().equals(s.getModeratorId()))
                .map(s -> toResponse(s, now))
                .filter(r -> q == null || q.isBlank() || (r.code() + " " + r.name()).toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT)))
                .sorted(desc ? order.reversed() : order)
                .toList();
    }

    public SemesterResponse toResponse(Semester s) {
        return toResponse(s, Instant.now());
    }

    private SemesterResponse toResponse(Semester s, Instant now) {
        ModeratorRef moderator = s.getModeratorId() == null ? null : users.findById(s.getModeratorId())
                .map(u -> new ModeratorRef(u.getId(), u.getFullName(), u.getEmail())).orElse(null);
        return new SemesterResponse(s.getCode(), s.getName(), s.getStartDate(), s.getEndDate(), s.getFormationOpensAt(),
                s.getDefaultDeadline(), moderator, semesterStudents.countBySemesterCode(s.getCode()),
                groups.countActiveBySemester(s.getCode()), subjects.countBySemesterCode(s.getCode()));
    }

    private Semester lock(String code) {
        return semesters.lockByCode(code).orElseThrow(() -> ResourceNotFoundException.of("Semester", code));
    }

    private static void requireDates(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BadRequestException("The start and end dates are required");
        }
        if (!end.isAfter(start)) {
            throw new BadRequestException("The semester must end after it starts");
        }
    }

    private static void requireTimeline(Semester s) {
        if (s.getFormationOpensAt() != null && s.getDefaultDeadline() != null
                && !s.getDefaultDeadline().isAfter(s.getFormationOpensAt())) {
            throw new BadRequestException("The common deadline must come after the opening date");
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

    /** Audit rows identify entities by UUID; a semester's is derived from its code. */
    private static UUID entityId(String code) {
        return UUID.nameUUIDFromBytes(("semester:" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
