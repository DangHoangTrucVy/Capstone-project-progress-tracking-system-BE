package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;

import java.util.List;
import java.util.UUID;

public record StudentGroupResponse(
        UUID id,
        String groupCode,
        UUID topicId,
        String topicTitle,
        UUID supervisorId,
        String supervisorName,
        String semester,
        GroupStatus status,
        int memberCount,
        boolean full,
        List<GroupMemberResponse> members
) {
    public static StudentGroupResponse from(StudentGroup g, long memberCount) {
        return from(g, memberCount, null);
    }

    public static StudentGroupResponse from(StudentGroup g, long memberCount, List<GroupMemberResponse> members) {
        UUID topicId = g.getTopic() != null ? g.getTopic().getId() : null;
        String topicTitle = g.getTopic() != null ? g.getTopic().getTitle() : null;
        UUID supervisorId = g.getSupervisor() != null ? g.getSupervisor().getId() : null;
        String supervisorName = g.getSupervisor() != null ? g.getSupervisor().getFullName() : null;
        return new StudentGroupResponse(g.getId(), g.getGroupCode(), topicId, topicTitle,
                supervisorId, supervisorName, g.getSemester(), g.getStatus(),
                (int) memberCount, memberCount >= StudentGroupService.MAX_MEMBERS, members);
    }
}
