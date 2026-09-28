package com.capstone.tracking.review;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.group.StudentGroup;
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

/** One group's review in one {@link ReviewRound}: when, where, the panel, and the panel's result. */
@Entity
@Table(name = "review_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewRound round;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false)
    private int durationMinutes;

    /** Room or meeting link. */
    @Column(nullable = false)
    private String location;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReviewPanelMember> panel = new ArrayList<>();

    /** Null until the result is recorded. */
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    /** Review 3 only. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ClosedCouncilOutcome outcome;

    /** REVISE_BEFORE_DEFENSE_1: the date the revision is due. */
    private Instant revisionDeadline;

    /** REVISE_BEFORE_DEFENSE_1: set when the chair/supervisor confirms the revision was done. */
    private Instant revisionCompletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    public Instant endsAt() {
        return scheduledAt.plusSeconds(durationMinutes * 60L);
    }

    public Optional<ReviewPanelMember> chair() {
        return panel.stream().filter(ReviewPanelMember::isChair).findFirst();
    }

    public boolean hasReviewer(User user) {
        return panel.stream().anyMatch(m -> m.getReviewer().getId().equals(user.getId()));
    }

    /** Cleared for Defense 1: ready outright, or the requested revision was confirmed. */
    public boolean clearsDefense1() {
        return outcome == ClosedCouncilOutcome.READY_FOR_DEFENSE_1
                || (outcome == ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1 && revisionCompletedAt != null);
    }

    /** Goes to Defense 2: deferred, or the revision was not confirmed by its deadline. */
    public boolean sendsToDefense2(Instant now) {
        return outcome == ClosedCouncilOutcome.DEFER_TO_DEFENSE_2
                || (outcome == ClosedCouncilOutcome.REVISE_BEFORE_DEFENSE_1 && revisionCompletedAt == null
                    && revisionDeadline != null && now.isAfter(revisionDeadline));
    }
}
