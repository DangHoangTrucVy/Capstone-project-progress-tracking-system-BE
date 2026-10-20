package com.capstone.tracking.team;

import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds {@link GroupResponse}s with the profile visibility of 8.1. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupViews {

    private final GroupMemberRepository members;
    private final GroupCore core;
    private final Access access;

    public GroupResponse view(StudentGroup group, User viewer) {
        return view(group, members.findActive(group.getId()), viewer, Instant.now());
    }

    public List<GroupResponse> views(List<StudentGroup> groups, User viewer) {
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GroupMember>> byGroup = members.findActiveIn(groups.stream().map(StudentGroup::getId).toList())
                .stream().collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        Instant now = Instant.now();
        return groups.stream().map(g -> view(g, byGroup.getOrDefault(g.getId(), List.of()), viewer, now)).toList();
    }

    private GroupResponse view(StudentGroup g, List<GroupMember> active, User viewer, Instant now) {
        FormationScope scope = g.getScope();
        boolean staff = viewer != null && access.isModerator(scope.semesterCode(), viewer);
        boolean teammate = viewer != null && active.stream().anyMatch(m -> m.getUser().getId().equals(viewer.getId()));
        GroupMember leader = active.stream().filter(GroupMember::isLeader).findFirst().orElse(null);
        int max = scope.getSubject().getMaxGroupSize();
        List<GroupResponse.Member> list = active.stream()
                .map(m -> new GroupResponse.Member(m.getUser().getId(), m.getUser().getFullName(),
                        m.getUser().getStudentCode(), m.isLeader(), m.getJoinedAt(),
                        ProfileView.visible(m.getUser(), staff || teammate)))
                .toList();
        return new GroupResponse(g.getId(), g.getCode(), g.getName(), g.getWantedSkills(), scope.getId(),
                scope.getSubject().getId(), scope.getSubject().getCode(), scope.semesterCode(), scope.getMajor().getCode(),
                scope.getMajor().getName(), scope.getPhase(), active.size(), scope.getSubject().getMinGroupSize(), max,
                core.state(g, active.size(), now), active.size() >= max, g.isLocked(), g.isFinalized(), g.getFinalizedAt(), g.getDissolvedReason(),
                g.getDissolvedAt(), leader == null ? null : leader.getUser().getId(),
                leader == null ? null : leader.getUser().getFullName(), list, g.getCreatedAt());
    }
}
