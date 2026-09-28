package com.capstone.tracking.proposal.dto;

import com.capstone.tracking.proposal.ProposalRound;

import java.time.Instant;
import java.util.UUID;

public record ProposalRoundResponse(
        UUID id,
        String semester,
        int roundNumber,
        Instant opensAt,
        Instant closesAt,
        boolean closed,
        boolean openNow
) {
    public static ProposalRoundResponse from(ProposalRound r) {
        return new ProposalRoundResponse(r.getId(), r.getSemester(), r.getRoundNumber(), r.getOpensAt(), r.getClosesAt(),
                r.isClosed(), r.isOpenAt(Instant.now()));
    }
}
