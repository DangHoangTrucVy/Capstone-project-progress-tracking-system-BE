package com.capstone.tracking.roster;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.FormationScopeRepository;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.team.GroupMemberRepository;
import com.capstone.tracking.team.StudentGroup;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The student tabs of the semester screen (4.3): all students and those without a group (with the reason); search,
 * filters, paging and editing a student's contact details. Admin and the semester's Moderator.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RosterService {

    private final EnrollmentRepository enrollments;
    private final SemesterStudentRepository semesterStudents;
    private final FormationScopeRepository scopes;
    private final UserRepository users;
    private final GroupMemberRepository members;
    private final Access access;

    public record StudentRow(UUID enrollmentId, UUID semesterStudentId, UUID userId, String studentCode,
                             String fullName, String email, String phone, UUID majorId, String majorCode,
                             String majorName, int currentTerm, Eligibility eligibility, String eligibilityReason,
                             boolean hasGroup, UUID roomId, String groupCode, String roomName, boolean leader,
                             NoGroupReason noGroupReason) {}

    public record MajorRow(UUID id, String code, String name, long students) {}

    public record StudentUpdate(@jakarta.validation.constraints.Size(max = 255) String fullName,
                                @jakarta.validation.constraints.Email String email,
                                @jakarta.validation.constraints.Size(max = 20) String phone) {}

    public Page<StudentRow> students(String semesterCode, UUID majorId, Boolean hasGroup, Eligibility eligibility,
                                     String q, Pageable pageable, User viewer) {
        access.requireModerator(semesterCode, viewer);
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Enrollment> page = enrollments.search(semesterCode, majorId, eligibility, hasGroup, like, pageable);
        Map<UUID, GroupMember> memberships = page.isEmpty() ? Map.of()
                : members.findActiveByEnrollments(page.map(Enrollment::getId).toList()).stream()
                        .collect(Collectors.toMap(m -> m.getEnrollment().getId(), Function.identity()));
        return page.map(e -> row(e, memberships.get(e.getId())));
    }

    public List<MajorRow> majors(String semesterCode, User viewer) {
        access.requireModerator(semesterCode, viewer);
        return scopes.findBySemester(semesterCode).stream()
                .map(s -> new MajorRow(s.getMajor().getId(), s.getMajor().getCode(), s.getMajor().getName(),
                        enrollments.countByScopeId(s.getId())))
                .toList();
    }

    /** Edits a student's contact details in every phase. Major and term never change by hand (3.3). */
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
            if (update.email().isBlank()) {
                throw new BadRequestException("The email is required");
            }
            String email = update.email().trim().toLowerCase(Locale.ROOT);
            users.findByEmailIgnoreCase(email).ifPresent(other -> {
                throw new ConflictException(other.getRole() == Role.STUDENT
                        ? "This email belongs to another student" : "This email belongs to a Moderator or Admin account");
            });
            user.setEmail(email);
        }
        if (update.phone() != null) {
            user.setPhone(update.phone().isBlank() ? null : update.phone().trim());
        }
        Enrollment e = enrollments.findBySemesterStudentId(ss.getId()).orElse(null);
        if (e == null) {
            return new StudentRow(null, ss.getId(), user.getId(), user.getStudentCode(), user.getFullName(),
                    user.getEmail(), user.getPhone(), ss.getMajor().getId(), ss.getMajor().getCode(),
                    ss.getMajor().getName(), ss.getCurrentTerm(), null, null, false, null, null, null, false, null);
        }
        return row(e, members.findActiveByEnrollment(e.getId()).orElse(null));
    }

    private StudentRow row(Enrollment e, GroupMember m) {
        User u = e.getUser();
        SemesterStudent ss = e.getSemesterStudent();
        StudentGroup g = m == null ? null : m.getGroup();
        return new StudentRow(e.getId(), ss.getId(), u.getId(), u.getStudentCode(), u.getFullName(), u.getEmail(),
                u.getPhone(), ss.getMajor().getId(), ss.getMajor().getCode(), ss.getMajor().getName(),
                ss.getCurrentTerm(), e.getEligibility(), e.getEligibilityReason(), g != null,
                g == null ? null : g.getId(), g == null ? null : g.getCode(), g == null ? null : g.getName(),
                m != null && m.isLeader(),
                g == null ? (e.isIneligible() ? NoGroupReason.INELIGIBLE : e.getNoGroupReason()) : null);
    }
}
