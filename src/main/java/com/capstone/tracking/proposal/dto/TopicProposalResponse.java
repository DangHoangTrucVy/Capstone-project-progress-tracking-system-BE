package com.capstone.tracking.proposal.dto;

import com.capstone.tracking.proposal.ProposalPolicy;
import com.capstone.tracking.proposal.ProposalStatus;
import com.capstone.tracking.proposal.TopicProposal;
import com.capstone.tracking.proposal.TopicProposalItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Built inside the service transaction (reads lazy associations). {@code remainingRounds} is how many council
 * reviews the group still has after this one.
 */
public record TopicProposalResponse(
        UUID id,
        UUID groupId,
        String groupCode,
        int round,
        int remainingRounds,
        ProposalStatus status,
        UUID submittedById,
        String submittedByName,
        Instant submittedAt,
        List<Item> topics,
        UUID selectedItemId,
        String selectedTitle,
        String instructorNote,
        String forwardedByName,
        Instant forwardedAt,
        Instant councilDeadline,
        boolean councilOverdue,
        String decidedByName,
        Instant decidedAt,
        String councilFeedback,
        UUID approvedTopicId
) {
    public record Item(UUID id, String title, String description, int sortOrder, boolean selected) {
        static Item from(TopicProposalItem i) {
            return new Item(i.getId(), i.getTitle(), i.getDescription(), i.getSortOrder(), i.isSelected());
        }
    }

    public static TopicProposalResponse from(TopicProposal p) {
        TopicProposalItem selected = p.selectedItem().orElse(null);
        return new TopicProposalResponse(
                p.getId(),
                p.getGroup().getId(),
                p.getGroup().getGroupCode(),
                p.getRound(),
                ProposalPolicy.MAX_ROUNDS - p.getRound(),
                p.getStatus(),
                p.getSubmittedBy().getId(),
                p.getSubmittedBy().getFullName(),
                p.getSubmittedAt(),
                p.getItems().stream().map(Item::from).toList(),
                selected != null ? selected.getId() : null,
                selected != null ? selected.getTitle() : null,
                p.getInstructorNote(),
                p.getForwardedBy() != null ? p.getForwardedBy().getFullName() : null,
                p.getForwardedAt(),
                p.getCouncilDeadline(),
                p.isCouncilOverdue(),
                p.getDecidedBy() != null ? p.getDecidedBy().getFullName() : null,
                p.getDecidedAt(),
                p.getCouncilFeedback(),
                p.getApprovedTopic() != null ? p.getApprovedTopic().getId() : null);
    }
}
