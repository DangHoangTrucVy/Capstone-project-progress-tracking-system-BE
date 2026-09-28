package com.capstone.tracking.proposal;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One submission of a group's topic list and its way through pre-review and the Council (Giai đoạn 2).
 * {@code round} counts the group's council reviews: 1 for the first list, 2-4 for resubmissions after a rejection.
 */
@Entity
@Table(name = "topic_proposals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicProposal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @Column(nullable = false)
    private int round;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProposalStatus status = ProposalStatus.PENDING_INSTRUCTOR;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by", nullable = false)
    private User submittedBy;

    @Column(nullable = false)
    private Instant submittedAt;

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc")
    @Builder.Default
    private List<TopicProposalItem> items = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String instructorNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "forwarded_by")
    private User forwardedBy;

    private Instant forwardedAt;

    /** forwardedAt + 14 days (round 1) or + 10 days (rounds 2-4). */
    private Instant councilDeadline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    private Instant decidedAt;

    @Column(columnDefinition = "TEXT")
    private String councilFeedback;

    /** The official Topic created on approval. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_topic_id")
    private Topic approvedTopic;

    public Optional<TopicProposalItem> selectedItem() {
        return items.stream().filter(TopicProposalItem::isSelected).findFirst();
    }

    public boolean isCouncilOverdue() {
        return status == ProposalStatus.PENDING_COUNCIL && councilDeadline != null && Instant.now().isAfter(councilDeadline);
    }
}
