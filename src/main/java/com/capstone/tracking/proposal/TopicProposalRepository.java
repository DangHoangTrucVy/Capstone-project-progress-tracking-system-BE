package com.capstone.tracking.proposal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** No "(:x is null or ...)" queries: Postgres cannot type a bare null parameter (see ScheduleSlotService.search). */
public interface TopicProposalRepository extends JpaRepository<TopicProposal, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TopicProposal p where p.id = :id")
    Optional<TopicProposal> lockById(@Param("id") UUID id);

    List<TopicProposal> findByGroupIdOrderByRoundAsc(UUID groupId);

    Optional<TopicProposal> findFirstByGroupIdOrderByRoundDesc(UUID groupId);

    long countByGroupId(UUID groupId);

    boolean existsByGroupIdAndStatusIn(UUID groupId, Collection<ProposalStatus> statuses);

    Page<TopicProposal> findByStatusIn(Collection<ProposalStatus> statuses, Pageable pageable);

    Page<TopicProposal> findByGroup_Supervisor_IdAndStatusIn(UUID supervisorId, Collection<ProposalStatus> statuses,
                                                             Pageable pageable);

    /** Groups of a semester whose proposal was rejected in the given round: the ones a new window is for. */
    @Query("select p from TopicProposal p where p.group.semester = :semester and p.round = :round "
            + "and p.status = com.capstone.tracking.proposal.ProposalStatus.REJECTED")
    List<TopicProposal> findRejectedInRound(@Param("semester") String semester, @Param("round") int round);
}
