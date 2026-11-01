package com.capstone.tracking.team;

import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.StudentGate;
import com.capstone.tracking.roster.importing.SpreadsheetReader;
import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searching within one subject and one major (8.2): the Leader looks for students without a group by skill, name or
 * student code; a student looks for open groups by name and wanted skills. A private profile shows up by name and
 * code only, and its hidden skills are never used to match (8.1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final GroupCore core;
    private final GroupViews views;
    private final StudentGate gate;

    public List<ProfileView> candidates(UUID subjectId, String q, String skill, User viewer) {
        Enrollment mine = gate.enrollment(subjectId, viewer);
        StudentGroup ownGroup = core.groupOf(mine.getId());
        if (ownGroup == null || !core.isLeader(ownGroup.getId(), viewer.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Only a group Leader searches candidates");
        }
        String query = fold(q);
        String skillQuery = fold(skill);
        return enrollments.findWithoutGroup(mine.getScope().getId()).stream()
                .map(Enrollment::getUser)
                .filter(u -> !u.getId().equals(viewer.getId()))
                .filter(u -> query.isEmpty() || fold(u.getFullName()).contains(query)
                        || fold(u.getStudentCode()).contains(query))
                .filter(u -> skillQuery.isEmpty() || (!u.isProfilePrivate() && fold(u.getSkills()).contains(skillQuery)))
                .map(u -> ProfileView.visible(u, false))
                .toList();
    }

    public List<GroupResponse> openGroups(UUID subjectId, String q, String skill, User viewer) {
        Enrollment mine = gate.enrollment(subjectId, viewer);
        String query = fold(q);
        String skillQuery = fold(skill);
        Instant now = Instant.now();
        List<StudentGroup> open = groups.findActiveByScope(mine.getScope().getId()).stream()
                .filter(g -> core.state(g, core.size(g.getId()), now) == GroupState.OPEN)
                .filter(g -> query.isEmpty() || fold(g.getName()).contains(query) || fold(g.getCode()).contains(query))
                .filter(g -> skillQuery.isEmpty() || fold(g.getWantedSkills()).contains(skillQuery))
                .toList();
        return views.views(open, viewer);
    }

    private static String fold(String s) {
        return s == null ? "" : SpreadsheetReader.fold(s);
    }
}
