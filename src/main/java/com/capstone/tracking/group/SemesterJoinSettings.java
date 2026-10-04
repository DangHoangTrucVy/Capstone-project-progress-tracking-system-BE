package com.capstone.tracking.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-semester group formation settings, Admin-configurable: how long an Apply/Invite stays open (YC14, 48 hours when
 * absent) and the cut-off after which students no longer form, join or leave groups themselves (YC17, YC21).
 */
@Entity
@Table(name = "semester_join_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SemesterJoinSettings {

    public static final int DEFAULT_TTL_HOURS = 48;

    @Id
    @Column(length = 20)
    private String semester;

    @Column(nullable = false)
    private int ttlHours;

    /** Null: no cut-off. */
    private Instant formationDeadline;

    public SemesterJoinSettings(String semester, int ttlHours) {
        this(semester, ttlHours, null);
    }
}
