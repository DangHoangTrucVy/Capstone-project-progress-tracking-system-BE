package com.capstone.tracking.proposal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProposalRoundRepository extends JpaRepository<ProposalRound, UUID> {

    Optional<ProposalRound> findBySemesterAndRoundNumber(String semester, int roundNumber);

    List<ProposalRound> findBySemesterOrderByRoundNumberAsc(String semester);

    List<ProposalRound> findAllByOrderBySemesterAscRoundNumberAsc();
}
