package com.capstone.tracking.audit;

import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable history of the operations listed in mục 11. Deliberately does NOT extend BaseEntity: a row is written
 * once and never updated, so it has no updatedAt.
 */
@Entity
@Table(name = "system_audit_trail")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemAuditTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** e.g. "FormationScope", "StudentGroup", "Enrollment". */
    @Column(nullable = false)
    private String entityName;

    @Column(nullable = false)
    private UUID entityId;

    /** An {@link AuditAction} name; older rows hold the actions of the former system. */
    @Column(nullable = false, length = 40)
    private String action;

    /** Null: done by the system (automatic opening, locking at the deadline). */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "performed_by")
    private User performedBy;

    @Column(nullable = false)
    private Instant performedAt;

    @Column(name = "semester_code", length = 10)
    private String semesterCode;

    /** Free-form JSON snapshot of what changed. */
    @Column(columnDefinition = "TEXT")
    private String detailsJson;
}
