package com.capstone.tracking.team;

import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.StudentGate;
import com.capstone.tracking.roster.importing.SpreadsheetReader;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.team.dto.RoomResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searching within one semester and one major (8.2): a Leader looks for students without a room by skill, name or
 * student code; a student browses the rooms of their major by name and wanted skills. A private profile shows up by
 * name and code only, and its hidden skills are never used to match (8.1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private static final Set<RoomStatus> AVAILABLE = Set.of(RoomStatus.EMPTY, RoomStatus.OPEN, RoomStatus.LOCKED);

    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupMemberRepository members;
    private final com.capstone.tracking.semester.FormationScopeRepository scopes;
    private final GroupViews views;
    private final StudentGate gate;
    private final Access access;

    /** Students of the Leader's major without a room. The Moderator may search too, with a major. */
    public List<ProfileView> candidates(String semesterCode, UUID majorId, String q, String skill, User viewer) {
        UUID scopeId;
        boolean staff = access.isModerator(semesterCode, viewer);
        if (staff) {
            if (majorId == null) {
                return enrollments.findWithoutGroupInSemester(semesterCode).stream()
                        .map(Enrollment::getUser).filter(u -> matches(u, fold(q), fold(skill), true))
                        .map(u -> ProfileView.visible(u, true)).toList();
            }
            scopeId = scopes.findBySemesterCodeAndMajorId(semesterCode, majorId).map(s -> s.getId()).orElse(null);
            if (scopeId == null) {
                return List.of();
            }
        } else {
            Enrollment mine = gate.enrollment(semesterCode, viewer);
            boolean leader = members.findActiveByEnrollment(mine.getId()).map(GroupMember::isLeader).orElse(false);
            if (!leader) {
                throw new AccessDeniedException("Only a room's Leader searches for candidates");
            }
            scopeId = mine.getScope().getId();
        }
        String query = fold(q);
        String skillQuery = fold(skill);
        return enrollments.findWithoutGroup(scopeId).stream()
                .map(Enrollment::getUser)
                .filter(u -> !u.getId().equals(viewer.getId()))
                .filter(u -> matches(u, query, skillQuery, staff))
                .map(u -> ProfileView.visible(u, staff))
                .toList();
    }

    /** The rooms of the student's major, by name or wanted skill; {@code available} keeps the ones a student can enter. */
    public List<RoomResponse> rooms(String semesterCode, String q, String skill, boolean available, User viewer) {
        Enrollment mine = gate.enrollment(semesterCode, viewer);
        String query = fold(q);
        String skillQuery = fold(skill);
        List<StudentGroup> found = groups.findActiveByScope(mine.getScope().getId()).stream()
                .filter(g -> query.isEmpty() || fold(g.getName()).contains(query) || fold(g.getCode()).contains(query))
                .filter(g -> skillQuery.isEmpty() || fold(g.getWantedSkills()).contains(skillQuery))
                .toList();
        return views.views(found, viewer).stream().filter(r -> !available || AVAILABLE.contains(r.status())).toList();
    }

    private static boolean matches(User u, String query, String skillQuery, boolean staff) {
        boolean byName = query.isEmpty() || fold(u.getFullName()).contains(query) || fold(u.getStudentCode()).contains(query);
        boolean bySkill = skillQuery.isEmpty() || ((staff || !u.isProfilePrivate()) && fold(u.getSkills()).contains(skillQuery));
        return byName && bySkill;
    }

    private static String fold(String s) {
        return s == null ? "" : SpreadsheetReader.fold(s);
    }
}
