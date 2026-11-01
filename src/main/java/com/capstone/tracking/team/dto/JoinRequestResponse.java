package com.capstone.tracking.team.dto;

import com.capstone.tracking.team.JoinRequest;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Instant;
import java.util.UUID;

/**
 * An Apply or Invite. {@code student} is the applicant or the invited student; the group sees an applicant's full
 * profile (even a private one) only while the Apply is pending (8.1).
 */
public record JoinRequestResponse(
        UUID id,
        JoinRequest.Type type,
        JoinRequest.Status status,
        UUID groupId,
        String groupCode,
        String groupName,
        ProfileView student,
        String message,
        String rejectReason,
        JoinRequest.CancelReason cancelReason,
        UUID createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant respondedAt,
        Long likes,
        Boolean likedByMe
) {
}
