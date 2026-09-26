package com.capstone.tracking.artifact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ArtifactSubmissionRepository extends JpaRepository<ArtifactSubmission, UUID> {

    Page<ArtifactSubmission> findByGroupId(UUID groupId, Pageable pageable);

    Page<ArtifactSubmission> findByGroupIdAndMilestoneId(UUID groupId, UUID milestoneId, Pageable pageable);

    /** Backs the resubmission/versioning rule in ArtifactSubmissionService#create. */
    Optional<ArtifactSubmission> findTopByGroup_IdAndTitleOrderByVersionDesc(UUID groupId, String title);
}
