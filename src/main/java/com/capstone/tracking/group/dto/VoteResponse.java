package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.GroupApplicationVote;
import com.capstone.tracking.group.VoteType;
import java.time.Instant;
import java.util.UUID;

public record VoteResponse(UUID voterId, String voterName, VoteType vote, String comment, Instant votedAt) {
    public static VoteResponse from(GroupApplicationVote v) {
        return new VoteResponse(v.getVoter().getId(), v.getVoter().getFullName(), v.getVote(), v.getComment(),
                v.getUpdatedAt());
    }
}
