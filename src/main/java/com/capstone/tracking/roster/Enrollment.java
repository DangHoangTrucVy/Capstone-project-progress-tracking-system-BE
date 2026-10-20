package com.capstone.tracking.roster;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.user.User;
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
 * A student's eligibility in Capstone. Whether they have a group is computed from group_members;
 * finalization belongs to StudentGroup and is never a student status.
 */
@Entity
@Table(name = "enrollments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Enrollment extends BaseEntity {

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scope_id", nullable = false)
    private FormationScope scope;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "semester_student_id", nullable = false)
    private SemesterStudent semesterStudent;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Eligibility eligibility = Eligibility.UNDECIDED;

    @Column(length = 500)
    private String eligibilityReason;

    private UUID eligibilityUpdatedBy;

    private Instant eligibilityUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private NoGroupReason noGroupReason;

    public boolean isIneligible() {
        return eligibility == Eligibility.INELIGIBLE;
    }
}
