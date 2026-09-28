package com.capstone.tracking.semester;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "semester_calendars")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SemesterCalendar {
    @Id @Column(length = 20) private String semester;
    @Column(nullable = false) private LocalDate startDate;
}
