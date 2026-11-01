package com.capstone.tracking.subject;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    List<Subject> findBySemesterCodeOrderByCodeAsc(String semesterCode);

    boolean existsBySemesterCodeAndCodeIgnoreCase(String semesterCode, String code);

    java.util.Optional<Subject> findBySemesterCodeAndCodeIgnoreCase(String semesterCode, String code);

    long countBySemesterCode(String semesterCode);
}
