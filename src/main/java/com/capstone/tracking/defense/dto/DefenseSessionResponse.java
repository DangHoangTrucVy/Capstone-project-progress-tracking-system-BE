package com.capstone.tracking.defense.dto;

import com.capstone.tracking.defense.DefenseCommitteeMember;
import com.capstone.tracking.defense.DefenseSession;
import com.capstone.tracking.defense.DefenseStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record DefenseSessionResponse(
        UUID id,
        UUID groupId,
        String groupCode,
        String topicTitle,
        int attempt,
        Instant scheduledAt,
        int durationMinutes,
        String room,
        List<CommitteeMember> committee,
        DefenseStatus status,
        Double score,
        String feedback,
        Instant gradedAt
) {
    public record CommitteeMember(UUID userId, String fullName, String email, boolean chair) {
        static CommitteeMember from(DefenseCommitteeMember m) {
            return new CommitteeMember(m.getMember().getId(), m.getMember().getFullName(), m.getMember().getEmail(), m.isChair());
        }
    }

    public static DefenseSessionResponse from(DefenseSession s) {
        return new DefenseSessionResponse(s.getId(), s.getGroup().getId(), s.getGroup().getGroupCode(),
                s.getGroup().getTopic() != null ? s.getGroup().getTopic().getTitle() : null,
                s.getAttempt(), s.getScheduledAt(), s.getDurationMinutes(), s.getRoom(),
                s.getCommittee().stream().map(CommitteeMember::from)
                        .sorted(Comparator.comparing(CommitteeMember::chair).reversed()).toList(),
                s.getStatus(), s.getScore(), s.getFeedback(), s.getGradedAt());
    }
}
