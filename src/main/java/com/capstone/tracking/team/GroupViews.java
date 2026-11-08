package com.capstone.tracking.team;

import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.FormationScope;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.team.dto.RoomResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds {@link RoomResponse}s with the profile visibility of 8.1. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupViews {

    private final GroupMemberRepository members;
    private final SemesterRepository semesters;
    private final GroupCore core;
    private final Access access;

    public RoomResponse view(StudentGroup group, User viewer) {
        Semester semester = semesters.findById(group.getScope().semesterCode()).orElseThrow();
        return view(group, members.findActive(group.getId()), semester, viewer, Instant.now());
    }

    public List<RoomResponse> views(List<StudentGroup> groups, User viewer) {
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GroupMember>> byGroup = members.findActiveIn(groups.stream().map(StudentGroup::getId).toList())
                .stream().collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        Map<String, Semester> bySemester = new HashMap<>();
        Instant now = Instant.now();
        return groups.stream().map(g -> view(g, byGroup.getOrDefault(g.getId(), List.of()),
                bySemester.computeIfAbsent(g.getScope().semesterCode(), c -> semesters.findById(c).orElseThrow()),
                viewer, now)).toList();
    }

    private RoomResponse view(StudentGroup g, List<GroupMember> active, Semester semester, User viewer, Instant now) {
        FormationScope scope = g.getScope();
        boolean staff = viewer != null && access.isModerator(semester, viewer);
        boolean teammate = viewer != null && active.stream().anyMatch(m -> m.getUser().getId().equals(viewer.getId()));
        GroupMember leader = active.stream().filter(GroupMember::isLeader).findFirst().orElse(null);
        int max = semester.getMaxGroupSize();
        List<RoomResponse.Member> list = active.stream()
                .map(m -> new RoomResponse.Member(m.getUser().getId(), m.getUser().getFullName(),
                        m.getUser().getStudentCode(), m.isLeader(), m.getJoinedAt(),
                        ProfileView.visible(m.getUser(), staff || teammate)))
                .toList();
        RoomStatus status = core.status(g, active.size(), semester, now);
        return new RoomResponse(g.getId(), g.getCode(), g.getName(), g.getRoomNo(), g.getWantedSkills(),
                scope.semesterCode(), semester.getPhase(), scope.getMajor().getId(), scope.getMajor().getCode(),
                scope.getMajor().getName(), active.size(), g.getMinSize(), max,
                !g.isApproved() && !active.isEmpty() && active.size() < g.getMinSize(), status, status.label(),
                active.size() >= max, g.isLocked(), g.getFinalizeStatus(), g.getFinalizeRequestedAt(),
                staff || teammate ? g.getFinalizeNote() : null, g.isFinalized(), g.getFinalizedAt(),
                leader == null ? null : leader.getUser().getId(), leader == null ? null : leader.getUser().getFullName(),
                list, g.getCreatedAt());
    }
}
