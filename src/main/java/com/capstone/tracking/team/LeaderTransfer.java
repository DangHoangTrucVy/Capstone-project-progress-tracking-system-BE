package com.capstone.tracking.team;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The Leader hands the role to another member, who must accept (6.4). {@code leaveAfter}: transfer-and-leave, done
 * in one step when accepted. {@code afterFinalize}: made after the list was finalized, the only change allowed then.
 */
@Entity
@Table(name = "leader_transfers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaderTransfer extends BaseEntity {

    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @Column(nullable = false)
    @Builder.Default
    private boolean leaveAfter = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean afterFinalize = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    private Instant respondedAt;
}
