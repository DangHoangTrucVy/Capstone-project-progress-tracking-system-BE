package com.capstone.tracking.milestone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {

    List<Milestone> findBySemesterOrderBySequenceNoAsc(String semester);

    List<Milestone> findAllByOrderBySemesterAscSequenceNoAsc();

    boolean existsBySemesterAndCodeIgnoreCase(String semester, String code);
}
