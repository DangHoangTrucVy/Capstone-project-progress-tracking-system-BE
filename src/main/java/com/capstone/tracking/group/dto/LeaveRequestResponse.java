package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.LeaveRequestStatus;
import com.capstone.tracking.group.MemberLeaveRequest;
import java.time.Instant;
import java.util.UUID;

/** {@code leaderRequest}: sent by the group's Leader, so an Admin decides it. */
public record LeaveRequestResponse(
        UUID id,
        UUID groupId,
        UUID userId,
        String userFullName,
        LeaveRequestStatus status,
        String reason,
        Instant decidedAt,
        Instant createdAt,
        String groupCode,
        String userEmail,
        String studentCode,
        boolean leaderRequest
) {
    public static LeaveRequestResponse from(MemberLeaveRequest r) {
        return new LeaveRequestResponse(r.getId(), r.getGroup().getId(), r.getUser().getId(), r.getUser().getFullName(),
                r.getStatus(), r.getReason(), r.getDecidedAt(), r.getCreatedAt(), r.getGroup().getGroupCode(),
                r.getUser().getEmail(), JoinRequestResponse.studentCodeOf(r.getUser()), r.isLeaderRequest());
    }
}
