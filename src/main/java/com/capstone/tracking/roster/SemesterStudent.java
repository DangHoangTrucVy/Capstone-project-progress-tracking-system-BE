package com.capstone.tracking.roster;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.major.Major;
import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A student on a semester's imported list (4.1). A student on the lists of several semesters is retaking the subject:
 * each semester is a separate record (mục 9). Contact details (name, email, phone, student code) live on the user.
 */
@Entity
@Table(name = "semester_students")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SemesterStudent extends BaseEntity {

    @Column(name = "semester_code", nullable = false, length = 10)
    private String semesterCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "major_id", nullable = false)
    private Major major;

    /** "Kỳ học hiện tại": the student's term number, e.g. 8; decides which subjects they see. */
    @Column(nullable = false)
    private int currentTerm;
}
