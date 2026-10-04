package com.capstone.tracking.group;

import com.capstone.tracking.common.BaseEntity;
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

/** One Apply (student -> group) or Invite (leader -> student). An Invite never holds a seat (YC15). */
@Entity
@Table(name = "group_join_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupJoinRequest extends BaseEntity {

    /** EAGER for the same reason as {@link GroupMember#getUser()}: responses are built outside the transaction. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    /** The applicant (APPLY) or the invited student (INVITE). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JoinRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JoinRequestStatus status = JoinRequestStatus.PENDING;

    @Column(length = 1000)
    private String message;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** For an Invite created by approving an Apply: that Apply. */
    @Column(name = "source_application_id")
    private UUID sourceApplicationId;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant respondedAt;

    /** PENDING past its deadline counts as EXPIRED even before the sweeper has written that down. */
    public JoinRequestStatus effectiveStatus(Instant now) {
        return status == JoinRequestStatus.PENDING && !expiresAt.isAfter(now) ? JoinRequestStatus.EXPIRED : status;
    }
}
