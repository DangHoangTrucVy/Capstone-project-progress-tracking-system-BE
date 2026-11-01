package com.capstone.tracking.roster;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    List<ImportBatch> findBySemesterCodeOrderByImportedAtDesc(String semesterCode);

    List<ImportBatch> findByKindOrderByImportedAtDesc(ImportBatch.Kind kind);
}
