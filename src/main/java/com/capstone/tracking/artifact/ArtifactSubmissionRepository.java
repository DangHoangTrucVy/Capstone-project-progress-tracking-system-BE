package com.capstone.tracking.artifact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactSubmissionRepository extends JpaRepository<ArtifactSubmission, UUID> {

    Page<ArtifactSubmission> findByGroupId(UUID groupId, Pageable pageable);

    Page<ArtifactSubmission> findByGroupIdAndMilestoneId(UUID groupId, UUID milestoneId, Pageable pageable);

    /** Backs the resubmission/versioning rule in ArtifactSubmissionService#create. */
    Optional<ArtifactSubmission> findTopByGroup_IdAndTitleOrderByVersionDesc(UUID groupId, String title);

    /** Milestones the group has submitted at least one document for (drives the Overview progress bar). */
    @Query("select distinct a.milestone.id from ArtifactSubmission a where a.group.id = :groupId and a.milestone is not null")
    List<UUID> findSubmittedMilestoneIds(@Param("groupId") UUID groupId);
}
