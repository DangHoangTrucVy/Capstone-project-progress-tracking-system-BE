package com.capstone.tracking.review.dto;

import com.capstone.tracking.review.ClosedCouncilOutcome;
import com.capstone.tracking.review.ReviewPanelMember;
import com.capstone.tracking.review.ReviewRound;
import com.capstone.tracking.review.ReviewSession;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ReviewSessionResponse(
        UUID id,
        UUID groupId,
        String groupCode,
        ReviewRound round,
        Instant scheduledAt,
        int durationMinutes,
        String location,
        List<PanelMember> panel,
        boolean completed,
        Instant completedAt,
        String feedback,
        ClosedCouncilOutcome outcome,
        String outcomeLabel,
        Instant revisionDeadline,
        Instant revisionCompletedAt
) {
    public record PanelMember(UUID userId, String fullName, String email, boolean chair) {
        static PanelMember from(ReviewPanelMember m) {
            return new PanelMember(m.getReviewer().getId(), m.getReviewer().getFullName(), m.getReviewer().getEmail(), m.isChair());
        }
    }

    public static ReviewSessionResponse from(ReviewSession s) {
        return new ReviewSessionResponse(s.getId(), s.getGroup().getId(), s.getGroup().getGroupCode(), s.getRound(),
                s.getScheduledAt(), s.getDurationMinutes(), s.getLocation(),
                s.getPanel().stream().map(PanelMember::from)
                        .sorted(Comparator.comparing(PanelMember::chair).reversed()).toList(),
                s.getCompletedAt() != null, s.getCompletedAt(), s.getFeedback(), s.getOutcome(),
                s.getOutcome() != null ? s.getOutcome().label() : null,
                s.getRevisionDeadline(), s.getRevisionCompletedAt());
    }
}
