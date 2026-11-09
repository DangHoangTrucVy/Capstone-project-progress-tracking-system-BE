package com.capstone.tracking.semester;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.major.Major;
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
 * One major within one semester ("phạm vi ghép nhóm", 3.3): its students and its rooms. Students only join rooms of
 * their own major. It has no phase or deadline of its own: every major follows the semester's (3.3). It also numbers
 * the major's rooms (Room 01, Room 02...) and groups (SP26-SE-001...), and those numbers are never reused (7.6).
 */
@Entity
@Table(name = "formation_scopes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormationScope extends BaseEntity {

    @Column(name = "semester_code", nullable = false, length = 10)
    private String semesterCode;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "major_id", nullable = false)
    private Major major;

    /** The last room number given in this major. */
    @Column(nullable = false)
    @Builder.Default
    private int roomSeq = 0;

    /** The last group number issued in this major. */
    @Column(nullable = false)
    @Builder.Default
    private int groupSeq = 0;

    public String semesterCode() {
        return semesterCode;
    }
}
