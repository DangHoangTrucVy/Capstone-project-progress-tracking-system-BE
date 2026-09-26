package com.capstone.tracking.milestone;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A checkpoint of the semester (proposal, SRS, sprint demo...) that groups submit documents against. */
@Entity
@Table(name = "milestones")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Milestone extends BaseEntity {

    /** e.g. "SRS" — unique within a semester. */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Same format as StudentGroup.semester, e.g. "Fall2026". */
    @Column(nullable = false, length = 20)
    private String semester;

    private Instant dueDate;

    /** Display order within the semester. */
    @Column(nullable = false)
    private int sequenceNo;
}
