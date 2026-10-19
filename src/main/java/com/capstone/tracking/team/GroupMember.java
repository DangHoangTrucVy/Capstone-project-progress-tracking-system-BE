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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A membership. Rows are never deleted: leaving sets {@code leftAt} and the reason, which keeps the history. The
 * database allows one open membership per enrollment (one group per student per subject) and one Leader per group.
 */
@Entity
@Table(name = "group_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "is_leader", nullable = false)
    @Builder.Default
    private boolean leader = false;

    @Column(nullable = false)
    private Instant joinedAt;

    private Instant leftAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private LeftReason leftReason;

    public boolean isActive() {
        return leftAt == null;
    }

    public enum LeftReason {
        LEFT,
        KICKED,
        INELIGIBLE,
        MOVED,
        DISSOLVED,
        REMOVED_BY_MODERATOR,
        LEAVE_APPROVED
    }
}
