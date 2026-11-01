package com.capstone.tracking.major;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A student's major ("Ngành", 3.3), e.g. Kỹ thuật phần mềm, AI, Kinh tế. Students only form groups within their
 * major. Majors are never switched off: every semester has students in each of them.
 */
@Entity
@Table(name = "majors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Major extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, unique = true)
    private String name;
}
