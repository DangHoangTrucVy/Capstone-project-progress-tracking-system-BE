package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.GroupJoinRequest;
import com.capstone.tracking.group.JoinRequestStatus;
import com.capstone.tracking.group.JoinRequestType;
import com.capstone.tracking.user.Campus;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.UUID;

/**
 * An Apply or an Invite. {@code student} is the private recruiting profile (YC22): it is null when the viewer's right
 * to see it has ended (the Apply was rejected, withdrawn, expired or cancelled). The vote counts are only for the
 * group's members and Admin.
 */
public record JoinRequestResponse(
        UUID id,
        JoinRequestType type,
        JoinRequestStatus status,
        UUID groupId,
        String groupCode,
        String message,
        UUID sourceApplicationId,
        Instant expiresAt,
        Instant respondedAt,
        Instant createdAt,
        Profile student,
        Integer supportVotes,
        Integer opposeVotes
) {
    public record Profile(UUID userId, String fullName, String email, Campus campus, String avatarUrl, String bio,
                   String skills) {
        static Profile of(User u, boolean withPrivate) {
            return new Profile(u.getId(), u.getFullName(), u.getEmail(), u.getCampus(), u.getAvatarUrl(),
                    withPrivate ? u.getBio() : null, withPrivate ? u.getSkills() : null);
        }
    }

    /**
     * @param showStudent  whether the viewer may see who the student is
     * @param showPrivate  whether they may also see the private recruiting part (bio, skills), YC22
     */
    public static JoinRequestResponse from(GroupJoinRequest r, Instant now, boolean showStudent, boolean showPrivate,
                                           Integer support, Integer oppose) {
        return new JoinRequestResponse(r.getId(), r.getType(), r.effectiveStatus(now), r.getGroup().getId(),
                r.getGroup().getGroupCode(), r.getMessage(), r.getSourceApplicationId(), r.getExpiresAt(),
                r.getRespondedAt(), r.getCreatedAt(), showStudent ? Profile.of(r.getStudent(), showPrivate) : null, support, oppose);
    }
}
