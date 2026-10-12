package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.GroupStatus;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.UUID;

/** Every field is optional: what is left out stays unchanged. {@code leaderUserId} replaces the leader (YC20). */
public record StudentGroupUpdateRequest(
        UUID topicId,
        UUID supervisorId,
        GroupStatus status,
        @JsonAlias({"leaderId", "newLeaderUserId"})
        UUID leaderUserId
) {
    public StudentGroupUpdateRequest(UUID topicId, UUID supervisorId, GroupStatus status) {
        this(topicId, supervisorId, status, null);
    }
}
