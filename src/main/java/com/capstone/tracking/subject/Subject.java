package com.capstone.tracking.subject;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A subject that needs groups in a semester (3.2), e.g. Capstone Project. It is shown to the students whose current
 * term matches {@code targetTerm}; each major taking it is a separate {@link FormationScope}.
 */
@Entity
@Table(name = "subjects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subject extends BaseEntity {

    public static final String CAPSTONE_CODE = "CAP";
    public static final String CAPSTONE_NAME = "Capstone Project";
    public static final int DEFAULT_TARGET_TERM = 8;

    public static final int DEFAULT_MIN_GROUP_SIZE = 3;
    public static final int DEFAULT_MAX_GROUP_SIZE = 5;
    public static final int DEFAULT_REQUEST_TTL_MINUTES = 15;

    @Column(name = "semester_code", nullable = false, length = 10)
    private String semesterCode;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    /** The term ("kỳ học thứ mấy", e.g. 8) of the students who take this subject. */
    @Column(nullable = false)
    private int targetTerm;

    /** Smallest group that may be handed over (3); smaller groups are dissolved at the deadline. */
    @Column(nullable = false)
    @Builder.Default
    private int minGroupSize = DEFAULT_MIN_GROUP_SIZE;

    @Column(nullable = false)
    @Builder.Default
    private int maxGroupSize = DEFAULT_MAX_GROUP_SIZE;

    /** How long an Apply or Invite waits for an answer (3.5). */
    @Column(nullable = false)
    @Builder.Default
    private int requestTtlMinutes = DEFAULT_REQUEST_TTL_MINUTES;

    @Column(nullable = false)
    private UUID createdBy;
}
