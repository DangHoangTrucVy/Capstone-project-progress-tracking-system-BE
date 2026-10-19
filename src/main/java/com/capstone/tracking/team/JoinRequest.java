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
 * An Apply (student -> group; the Leader accepting it makes the student a member at once) or an Invite (Leader ->
 * student; the student accepting it joins). Neither holds a seat (6.2).
 */
@Entity
@Table(name = "join_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinRequest extends BaseEntity {

    public enum Type { APPLY, INVITE }

    /**
     * PENDING is the only open state. ACCEPTED: the student joined. REJECTED: refused by the receiver. WITHDRAWN: taken
     * back by the sender. EXPIRED: unanswered past its lifetime. CANCELLED: the system closed it (see cancelReason).
     */
    public enum Status { PENDING, ACCEPTED, REJECTED, WITHDRAWN, EXPIRED, CANCELLED }

    public enum CancelReason { JOINED_ANOTHER_GROUP, DEADLINE, INELIGIBLE, GROUP_DISSOLVED }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    /** The applicant (APPLY) or the invited student (INVITE). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(length = 1000)
    private String message;

    @Column(length = 1000)
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CancelReason cancelReason;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant respondedAt;

    /** PENDING past its lifetime counts as EXPIRED even before the sweeper writes it down. */
    public Status effectiveStatus(Instant now) {
        return status == Status.PENDING && !expiresAt.isAfter(now) ? Status.EXPIRED : status;
    }
}
