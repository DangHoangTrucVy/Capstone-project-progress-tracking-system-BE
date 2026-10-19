package com.capstone.tracking.roster;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.major.Major;
import com.capstone.tracking.major.MajorRepository;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
import com.capstone.tracking.team.GroupCore;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.GroupState;
import com.capstone.tracking.team.GroupViews;
import com.capstone.tracking.team.StudentGroup;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The list management screen (4.3), by semester, subject and major: all students, groups, students without a group
 * (with the reason); search, filters, paging and editing a student's details. Admin and the semester's Moderator.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RosterService {

    private final EnrollmentRepository enrollments;
    private final SemesterStudentRepository semesterStudents;
    private final SubjectRepository subjects;
    private final FormationScopeRepository scopes;
    private final MajorRepository majors;
    private final UserRepository users;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupCore core;
    private final GroupViews views;
    private final RosterSync sync;
    private final Access access;

    public record StudentRow(UUID enrollmentId, UUID semesterStudentId, UUID userId, String studentCode,
                             String fullName, String email, String phone, String majorCode, String majorName,
                             int currentTerm, Eligibility eligibility, String eligibilityReason, boolean hasGroup,
                             UUID groupId, String groupCode, String groupName, boolean leader, boolean finalized,
                             NoGroupReason noGroupReason) {}

    public record StudentUpdate(String fullName, String email, String phone, UUID majorId, Integer currentTerm) {}

    public Page<StudentRow> students(UUID subjectId, UUID majorId, Boolean hasGroup, Eligibility eligibility, String q,
                                     Pageable pageable, User viewer) {
        Subject subject = subject(subjectId);
        access.requireModerator(subject.getSemesterCode(), viewer);
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Enrollment> page = enrollments.search(subjectId, majorId, eligibility, hasGroup, like, pageable);
        Map<UUID, GroupMember> memberships = page.isEmpty() ? Map.of()
                : members.findActiveByEnrollments(page.map(Enrollment::getId).toList()).stream()
                        .collect(Collectors.toMap(m -> m.getEnrollment().getId(), Function.identity()));
        return page.map(e -> row(e, memberships.get(e.getId())));
    }

    /** The groups tab, with the "below the minimum size" filter of 7.1. */
    public List<GroupResponse> groups(UUID subjectId, UUID majorId, GroupState state, Integer maxSize, String q,
                                      User viewer) {
        Subject subject = subject(subjectId);
        access.requireModerator(subject.getSemesterCode(), viewer);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return views.views(groups.findActiveBySubject(subjectId).stream()
                        .filter(g -> majorId == null || g.getScope().getMajor().getId().equals(majorId))
                        .filter(g -> query.isEmpty() || g.getName().toLowerCase(Locale.ROOT).contains(query)
                                || g.getCode().toLowerCase(Locale.ROOT).contains(query))
                        .toList(), viewer).stream()
                .filter(g -> state == null || g.state() == state || (state == GroupState.FULL && g.full())
                        || (state == GroupState.LOCKED && g.locked()))
                .filter(g -> maxSize == null || g.size() <= maxSize)
                .toList();
    }

    /**
     * Edits a student's details. Contact details always; major and term only while every scope concerned is still
     * being prepared (3.3: no change of major once formation started).
     */
    @Transactional
    public StudentRow update(UUID semesterStudentId, StudentUpdate update, User viewer) {
        SemesterStudent ss = semesterStudents.findById(semesterStudentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", semesterStudentId));
        access.requireModerator(ss.getSemesterCode(), viewer);
        User user = ss.getUser();
        if (update.fullName() != null && !update.fullName().isBlank()) {
            user.setFullName(update.fullName().trim());
        }
        if (update.email() != null && !update.email().equalsIgnoreCase(user.getEmail())) {
            String email = update.email().trim().toLowerCase(Locale.ROOT);
            users.findByEmailIgnoreCase(email).ifPresent(other -> {
                throw new ConflictException(other.getRole() == Role.STUDENT
                        ? "This email belongs to another student" : "This email belongs to a lecturer or Admin account");
            });
            user.setEmail(email);
        }
        if (update.phone() != null) {
            user.setPhone(update.phone().isBlank() ? null : update.phone().trim());
        }
        boolean majorChange = update.majorId() != null && !update.majorId().equals(ss.getMajor().getId());
        boolean termChange = update.currentTerm() != null && update.currentTerm() != ss.getCurrentTerm();
        if (majorChange || termChange) {
            boolean started = enrollments.findBySemesterStudentId(ss.getId()).stream()
                    .anyMatch(e -> e.getScope().getPhase() != ScopePhase.PREPARING);
            Major major = majorChange ? majors.findById(update.majorId())
                    .orElseThrow(() -> ResourceNotFoundException.of("Major", update.majorId())) : ss.getMajor();
            int term = termChange ? update.currentTerm() : ss.getCurrentTerm();
            if (term < 1 || term > 12) {
                throw new BadRequestException("The term must be between 1 and 12");
            }
            boolean targetStarted = subjects.findBySemesterCodeOrderByCodeAsc(ss.getSemesterCode()).stream()
                    .filter(s -> s.getTargetTerm() == term)
                    .map(s -> scopes.findBySubjectIdAndMajorId(s.getId(), major.getId()).orElse(null))
                    .anyMatch(s -> s != null && s.getPhase() != ScopePhase.PREPARING);
            if (started || targetStarted) {
                throw new ApiException(HttpStatus.CONFLICT, "SCOPE_STARTED",
                        "Group formation has started: the major and term can no longer change");
            }
            ss.setMajor(major);
            ss.setCurrentTerm(term);
            sync.syncStudent(ss);
        }
        return enrollments.findBySemesterStudentId(ss.getId()).stream().findFirst()
                .map(e -> row(e, core.membershipOf(e.getId())))
                .orElse(new StudentRow(null, ss.getId(), user.getId(), user.getStudentCode(), user.getFullName(),
                        user.getEmail(), user.getPhone(), ss.getMajor().getCode(), ss.getMajor().getName(),
                        ss.getCurrentTerm(), null, null, false, null, null, null, false, false, null));
    }

    private StudentRow row(Enrollment e, GroupMember m) {
        User u = e.getUser();
        SemesterStudent ss = e.getSemesterStudent();
        StudentGroup g = m == null ? null : m.getGroup();
        return new StudentRow(e.getId(), ss.getId(), u.getId(), u.getStudentCode(), u.getFullName(), u.getEmail(),
                u.getPhone(), ss.getMajor().getCode(), ss.getMajor().getName(), ss.getCurrentTerm(), e.getEligibility(),
                e.getEligibilityReason(), g != null, g == null ? null : g.getId(), g == null ? null : g.getCode(),
                g == null ? null : g.getName(), m != null && m.isLeader(), e.isFinalized(),
                g == null ? (e.isIneligible() ? NoGroupReason.INELIGIBLE : e.getNoGroupReason()) : null);
    }

    private Subject subject(UUID subjectId) {
        return subjects.findById(subjectId).orElseThrow(() -> ResourceNotFoundException.of("Subject", subjectId));
    }
}
