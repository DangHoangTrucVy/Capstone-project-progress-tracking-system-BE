package com.capstone.tracking.roster;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One confirmed import (4.1, 4.2): who, when, which file, how many rows were new / updated / rejected. */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportBatch extends BaseEntity {

    public enum Kind { STUDENTS, ELIGIBILITY, LECTURERS }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "semester_code", length = 10)
    private String semesterCode;

    private String fileName;

    @Column(nullable = false)
    private UUID importedBy;

    @Column(nullable = false)
    private Instant importedAt;

    private int createdCount;
    private int updatedCount;
    private int errorCount;

    /** The rejected rows with their reasons, as JSON. */
    @Column(columnDefinition = "TEXT")
    private String errorsJson;
}
