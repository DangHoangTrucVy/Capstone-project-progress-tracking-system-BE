package com.capstone.tracking.team;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.subject.FormationScope;
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
 * A group of one formation scope (6). All members are of the scope's major. Open / Full are computed from the size and
 * the scope's phase ({@link GroupState}); Locked is the Leader's own flag and may hold together with Full (6.3).
 */
@Entity
@Table(name = "student_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentGroup extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scope_id", nullable = false)
    private FormationScope scope;

    /** e.g. FA26-CAP-SE-007, unique across the system. */
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** "Kỹ năng cần tuyển", searched by students looking for a group (8.2). */
    @Column(length = 500)
    private String wantedSkills;

    /** The Leader locked the group: no new Apply (6.3). */
    @Column(nullable = false)
    @Builder.Default
    private boolean locked = false;

    @Column(nullable = false)
    private UUID createdBy;

    /** Finalization belongs to the group; it is independent of Full and Leader lock. */
    @Column(nullable = false)
    @Builder.Default
    private boolean finalized = false;

    private Instant finalizedAt;

    private Instant dissolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DissolveReason dissolvedReason;

    public boolean isDissolved() {
        return dissolvedAt != null;
    }

    public enum DissolveReason {
        /** Fewer than the minimum size when the scope locked at its deadline (7.1). */
        TOO_SMALL_AT_DEADLINE,
        /** The last member left or was removed (6.4). */
        EMPTY,
        MODERATOR
    }
}
