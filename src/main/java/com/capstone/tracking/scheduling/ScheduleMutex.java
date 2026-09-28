package com.capstone.tracking.scheduling;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;

/** A shared database lock, including when no review/defense exists yet. */
@Entity
@Table(name = "schedule_mutex")
@NoArgsConstructor
public class ScheduleMutex {
    @Id private Integer id;
    public ScheduleMutex(Integer id) { this.id = id; }
}
