package com.capstone.tracking.group.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.UUID;

public record AddMemberRequest(
        UUID userId,
        String email,
        String identifier,
        @JsonAlias({"mssv", "studentId"})
        String studentCode,
        boolean isLeader
) {
    public AddMemberRequest(UUID userId, boolean isLeader) {
        this(userId, null, null, null, isLeader);
    }

    public AddMemberRequest(String identifier, boolean isLeader) {
        this(null, null, identifier, identifier, isLeader);
    }
}
