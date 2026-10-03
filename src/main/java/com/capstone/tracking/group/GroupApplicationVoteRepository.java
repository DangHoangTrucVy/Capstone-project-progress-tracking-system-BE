package com.capstone.tracking.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupApplicationVoteRepository extends JpaRepository<GroupApplicationVote, UUID> {

    List<GroupApplicationVote> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);

    Optional<GroupApplicationVote> findByApplicationIdAndVoterId(UUID applicationId, UUID voterId);
}
