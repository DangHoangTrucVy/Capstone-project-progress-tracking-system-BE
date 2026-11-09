package com.capstone.tracking.semester;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormationScopeRepository extends JpaRepository<FormationScope, UUID> {

    List<FormationScope> findBySemesterCodeOrderByMajor_CodeAsc(String semesterCode);

    Optional<FormationScope> findBySemesterCodeAndMajorId(String semesterCode, UUID majorId);

    default List<FormationScope> findBySemester(String semesterCode) {
        return findBySemesterCodeOrderByMajor_CodeAsc(semesterCode);
    }
}
