package com.capstone.tracking.team;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Asking to leave a group (6.4, 7.1). Before the deadline a member of a locked group asks the Leader
 * (PENDING_LEADER); if the Leader refuses, the member may complain to the Moderator (PENDING_MODERATOR, complaint).
 * A request still waiting for the Leader at the deadline becomes a complaint; after the deadline a member asks the
 * Moderator directly. CLOSED: the group was dissolved before a decision.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRequest extends BaseEntity {

    public enum Status { PENDING_LEADER, PENDING_MODERATOR, APPROVED, REJECTED, WITHDRAWN, CLOSED }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 1000)
    private String reason;

    /** The Moderator handles it as a complaint ("khiếu nại"). */
    @Column(nullable = false)
    @Builder.Default
    private boolean complaint = false;

    private UUID leaderDecidedBy;
    private Instant leaderDecidedAt;

    @Column(length = 1000)
    private String leaderNote;

    private UUID decidedBy;
    private Instant decidedAt;

    @Column(length = 1000)
    private String decisionNote;

    public boolean isOpen() {
        return status == Status.PENDING_LEADER || status == Status.PENDING_MODERATOR;
    }
}
