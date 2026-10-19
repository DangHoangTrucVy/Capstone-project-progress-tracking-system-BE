package com.capstone.tracking.semester;

import com.capstone.tracking.common.VnTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A semester (3.1), identified by its code: SP26, SU26, FA26. It carries the common timeline every formation scope
 * follows by default (opening date and deadline) and its one Moderator (3.6).
 */
@Entity
@Table(name = "semesters")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Semester {

    @Id
    @Column(length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** Common opening date of group formation; scopes open automatically from then (3.4). */
    private Instant formationOpensAt;

    /** Common deadline: the default of every scope without its own (3.5). */
    private Instant defaultDeadline;

    /** The lecturer coordinating the whole semester; null = the Admin does it. */
    private UUID moderatorId;

    private Instant archivedAt;

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

    /** Upcoming / Ongoing / Completed follow the dates (Vietnam time); Archived is set by the Admin. */
    public SemesterStatus status(Instant now) {
        if (archivedAt != null) {
            return SemesterStatus.ARCHIVED;
        }
        LocalDate today = now.atZone(VnTime.ZONE).toLocalDate();
        if (today.isBefore(startDate)) {
            return SemesterStatus.UPCOMING;
        }
        return today.isAfter(endDate) ? SemesterStatus.COMPLETED : SemesterStatus.ONGOING;
    }
}
