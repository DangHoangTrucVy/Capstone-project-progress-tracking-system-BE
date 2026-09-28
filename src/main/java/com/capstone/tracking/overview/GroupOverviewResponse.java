package com.capstone.tracking.overview;

import com.capstone.tracking.defense.dto.DefenseSessionResponse;
import com.capstone.tracking.group.GroupStatus;
import com.capstone.tracking.proposal.dto.TopicProposalResponse;
import com.capstone.tracking.review.dto.ReviewSessionResponse;
import com.capstone.tracking.warning.dto.WarningFlagResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the Leader's Overview screen shows in one call: topic approval status, the milestone progress bar,
 * warning-flag badges, upcoming meetings, reviews, defenses and where the group stands for the final defense.
 */
public record GroupOverviewResponse(
        UUID groupId,
        String groupCode,
        String semester,
        GroupStatus status,
        String topicTitle,
        String supervisorName,
        TopicProposalResponse latestProposal,
        MilestoneProgress milestoneProgress,
        Integer latestWeeklyProgress,
        List<WarningFlagResponse> activeWarningFlags,
        List<Meeting> upcomingMeetings,
        List<ReviewSessionResponse> reviews,
        List<DefenseSessionResponse> defenses,
        DefenseTrack defenseTrack,
        long unreadNotifications
) {
    /** Bước 4.1: the progress bar, computed from the semester's milestones the group has submitted documents for. */
    public record MilestoneProgress(int total, int submitted, int percentage, List<MilestoneItem> milestones) {
    }

    public record MilestoneItem(UUID id, String code, String name, Instant dueDate, boolean submitted, boolean overdue) {
    }

    public record Meeting(UUID bookingId, Instant startTime, Instant endTime, String instructorName, String meetingUrl) {
    }

    /** Where the group stands for the final defense (from Review 3 and the defenses so far). */
    public enum DefenseTrack {
        NOT_REVIEWED,
        DEFENSE_1,
        REVISE_BEFORE_DEFENSE_1,
        DEFENSE_2,
        COMPLETED,
        FAILED
    }
}
