package com.capstone.tracking.semester;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A semester (3.1), identified by its code: SP26, SU26, FA26. It is the central entity: it holds its student list,
 * rooms and groups, its rules (3.2), its phase (3.5), its one common deadline (3.7) and its one Moderator (3.8).
 */
@Entity
@Table(name = "semesters")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Semester {

    public static final int DEFAULT_MAX_GROUP_SIZE = 5;
    public static final int DEFAULT_MIN_ROOM_SIZE = 3;
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 3;

    @Id
    @Column(length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** The opening date of the timeline, for display; the Moderator still starts formation by hand (3.6). */
    private Instant formationOpensAt;

    /** The one common deadline of every major (3.3, 3.7). */
    private Instant deadline;

    /** The lecturer coordinating the whole semester; null = the Admin does it. */
    private UUID moderatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SemesterPhase phase = SemesterPhase.OPENING;

    // ------------------------------------------------------------------ rules (3.2)

    @Column(nullable = false)
    private int maxGroupSize = DEFAULT_MAX_GROUP_SIZE;

    /** Minimum size given to new rooms; each room carries its own, which the Moderator may change. */
    @Column(nullable = false)
    private int defaultMinRoomSize = DEFAULT_MIN_ROOM_SIZE;

    /** Requests to join a locked room one student may have waiting at once. */
    @Column(nullable = false)
    private int maxPendingRequests = DEFAULT_MAX_PENDING_REQUESTS;

    /** Lifetime of a request to join a locked room (and of an invite during formation); supplement invites never expire. */
    @Column(nullable = false)
    private int requestTtlMinutes;

    // ------------------------------------------------------------------ timeline of the phases

    private Instant formingStartedAt;
    /** Rooms were locked by the deadline ("sau deadline" from then on). */
    private Instant roomsLockedAt;
    @Column(name = "round1_finalized_at")
    private Instant round1FinalizedAt;
    @Column(name = "round1_finalized_by")
    private UUID round1FinalizedBy;
    @Column(name = "round2_finalized_at")
    private Instant round2FinalizedAt;
    @Column(name = "round2_finalized_by")
    private UUID round2FinalizedBy;
    private Instant closedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Semester(String code, String name, LocalDate startDate, LocalDate endDate) {
        this.code = code;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /**
     * "Trước deadline": formation is running and the rooms have not been locked by the deadline. The deadline instant
     * itself counts as past (3.6), even before the scheduler has locked the rooms.
     */
    public boolean isBeforeDeadline(Instant now) {
        return phase == SemesterPhase.FORMING && roomsLockedAt == null && deadline != null && now.isBefore(deadline);
    }

    public boolean isClosed() {
        return phase == SemesterPhase.CLOSED;
    }
}
