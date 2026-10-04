package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.LeaveRequestStatus;
import com.capstone.tracking.group.MemberLeaveRequest;
import java.time.Instant;
import java.util.UUID;

public record LeaveRequestResponse(
        UUID id,
        UUID groupId,
        UUID userId,
        String userFullName,
        LeaveRequestStatus status,
        String reason,
        Instant decidedAt,
        Instant createdAt
) {
    public static LeaveRequestResponse from(MemberLeaveRequest r) {
        return new LeaveRequestResponse(r.getId(), r.getGroup().getId(), r.getUser().getId(), r.getUser().getFullName(),
                r.getStatus(), r.getReason(), r.getDecidedAt(), r.getCreatedAt());
    }
}
