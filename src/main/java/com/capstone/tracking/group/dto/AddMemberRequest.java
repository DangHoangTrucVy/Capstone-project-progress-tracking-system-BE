package com.capstone.tracking.group.dto;

import java.util.UUID;

public record AddMemberRequest(
        UUID userId,
        String email,
        String identifier,
        boolean isLeader
) {
    public AddMemberRequest(UUID userId, boolean isLeader) {
        this(userId, null, null, isLeader);
    }

    public AddMemberRequest(String identifier, boolean isLeader) {
        this(null, null, identifier, isLeader);
    }
}
