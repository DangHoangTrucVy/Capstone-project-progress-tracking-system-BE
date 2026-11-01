package com.capstone.tracking.team.dto;

import com.capstone.tracking.team.LeaveRequest;
import java.time.Instant;
import java.util.UUID;

public record LeaveRequestResponse(
        UUID id,
        UUID groupId,
        String groupCode,
        String groupName,
        UUID userId,
        String fullName,
        String studentCode,
        LeaveRequest.Status status,
        boolean complaint,
        String reason,
        String leaderNote,
        Instant leaderDecidedAt,
        String decisionNote,
        Instant decidedAt,
        Instant createdAt
) {
    public static LeaveRequestResponse from(LeaveRequest r) {
        return new LeaveRequestResponse(r.getId(), r.getGroup().getId(), r.getGroup().getCode(), r.getGroup().getName(),
                r.getUser().getId(), r.getUser().getFullName(), r.getUser().getStudentCode(), r.getStatus(),
                r.isComplaint(), r.getReason(), r.getLeaderNote(), r.getLeaderDecidedAt(), r.getDecisionNote(),
                r.getDecidedAt(), r.getCreatedAt());
    }
}
