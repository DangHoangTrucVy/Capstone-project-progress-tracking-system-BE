package com.capstone.tracking.warning;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Bước 4.4: a supervisor's warning on a group that is behind schedule, or on one inactive member of it. Active flags
 * are shown as badges on the group's Overview until the supervisor resolves them.
 */
@Entity
@Table(name = "warning_flags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarningFlag extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    /** Set for MEMBER_INACTIVE flags only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private User member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WarningFlagType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WarningSeverity severity;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raised_by", nullable = false)
    private User raisedBy;

    @Column(nullable = false)
    private Instant raisedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    /** Null while the flag is active. */
    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String resolutionNote;

    public boolean isActive() {
        return resolvedAt == null;
    }
}
