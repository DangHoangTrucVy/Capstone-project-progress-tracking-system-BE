package com.capstone.tracking.team;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.semester.FormationScope;
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
 * A room, which becomes a group (6, 7.6, 12: one entity, so members, requests and invites never have to be copied).
 * The Moderator creates rooms of one major ahead of formation; students join them freely and the first one in is the
 * Leader. A room has no code until it becomes a group: when the Moderator approves the Leader's finalize request, or
 * when formation ends. An emptied room goes back to empty and is kept; empty rooms are closed when formation ends.
 */
@Entity
@Table(name = "student_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentGroup extends BaseEntity {

    public enum FinalizeStatus {
        NONE,
        /** The Leader pressed "báo chốt"; waiting for the Moderator. Members can neither join nor leave meanwhile. */
        REQUESTED,
        /** The Moderator approved: the room is a group with a code; no member joins or leaves by themselves. */
        APPROVED,
        /** The Moderator sent the request back, with a note. */
        RETURNED
    }

    public enum DissolveReason {
        /** Kept for history: groups dissolved before v3.0. */
        TOO_SMALL_AT_DEADLINE,
        EMPTY,
        MODERATOR,
        /** The room was still empty when formation ended (7.6). */
        CLOSED_EMPTY
    }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scope_id", nullable = false)
    private FormationScope scope;

    /** The group code, e.g. SP26-SE-001; null while it is still a room (7.6). */
    @Column(unique = true, length = 40)
    private String code;

    /** "Room 01", renamed freely by the Leader. */
    @Column(nullable = false, length = 100)
    private String name;

    /** The room's number in its major (Room 01 = 1). */
    private Integer roomNo;

    /** "Kỹ năng cần tuyển", searched by students looking for a room (8.2). */
    @Column(length = 500)
    private String wantedSkills;

    /** Below it the room is disbanded before round 1; the Moderator sets it per room (3.2). */
    @Column(nullable = false)
    private int minSize;

    /** The Leader locked the room: joining takes a request the Leader accepts (6.2). */
    @Column(nullable = false)
    @Builder.Default
    private boolean locked = false;

    @Column(nullable = false)
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FinalizeStatus finalizeStatus = FinalizeStatus.NONE;

    private Instant finalizeRequestedAt;
    private UUID finalizeRequestedBy;
    private Instant finalizeDecidedAt;
    private UUID finalizeDecidedBy;

    @Column(length = 1000)
    private String finalizeNote;

    /** "Đã chốt": the room became a group. It belongs to the group, never to its students (7.6). */
    @Column(nullable = false)
    @Builder.Default
    private boolean finalized = false;

    private Instant finalizedAt;

    /** Closed rooms (empty at the end of formation) and groups dissolved before v3.0; never shown again. */
    private Instant dissolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DissolveReason dissolvedReason;

    public boolean isDissolved() {
        return dissolvedAt != null;
    }

    public boolean isApproved() {
        return finalizeStatus == FinalizeStatus.APPROVED;
    }

    public boolean isFinalizeRequested() {
        return finalizeStatus == FinalizeStatus.REQUESTED;
    }
}
