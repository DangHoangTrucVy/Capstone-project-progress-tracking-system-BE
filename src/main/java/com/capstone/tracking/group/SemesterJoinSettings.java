package com.capstone.tracking.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** YC14: how long an Apply/Invite stays open in a semester; Admin-configurable, 48 hours when absent. */
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
}
