package com.capstone.tracking.proposal;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The submission window ("cổng đăng ký") an Admin opens for resubmission round 2, 3 or 4 of a semester (bước 2.4).
 * Round 1 needs no window. Closing it early sets {@code closed}.
 */
@Entity
@Table(name = "proposal_rounds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProposalRound extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String semester;

    @Column(nullable = false)
    private int roundNumber;

    @Column(nullable = false)
    private Instant opensAt;

    @Column(nullable = false)
    private Instant closesAt;

    @Column(nullable = false)
    private boolean closed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opened_by", nullable = false)
    private User openedBy;

    public boolean isOpenAt(Instant when) {
        return !closed && !when.isBefore(opensAt) && when.isBefore(closesAt);
    }
}
