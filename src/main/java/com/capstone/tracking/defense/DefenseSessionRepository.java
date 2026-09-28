package com.capstone.tracking.defense;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DefenseSessionRepository extends JpaRepository<DefenseSession, UUID> {

    List<DefenseSession> findByGroupIdOrderByAttemptAsc(UUID groupId);

    Optional<DefenseSession> findByGroupIdAndAttempt(UUID groupId, int attempt);

    List<DefenseSession> findByAttemptAndGroup_SemesterOrderByScheduledAtAsc(int attempt, String semester);

    List<DefenseSession> findByAttemptOrderByScheduledAtAsc(int attempt);

    @Query("select distinct s from DefenseSession s join s.committee m where m.member.id = :memberId order by s.scheduledAt asc")
    List<DefenseSession> findByCommitteeMember(@Param("memberId") UUID memberId);

    /** Defenses starting in [earliestStart, end): candidates for room / committee / parallelism checks. */
    List<DefenseSession> findByScheduledAtGreaterThanEqualAndScheduledAtLessThan(Instant earliestStart, Instant end);
}
