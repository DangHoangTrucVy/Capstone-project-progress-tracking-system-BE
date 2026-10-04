package com.capstone.tracking.defense;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DefenseSessionRepository extends JpaRepository<DefenseSession, UUID> {

    List<DefenseSession> findByScheduledAtLessThan(Instant end);

    List<DefenseSession> findByGroupIdOrderByAttemptAsc(UUID groupId);

    Optional<DefenseSession> findByGroupIdAndAttempt(UUID groupId, int attempt);

    List<DefenseSession> findByAttemptAndGroup_SemesterOrderByScheduledAtAsc(int attempt, String semester);

    List<DefenseSession> findByAttemptOrderByScheduledAtAsc(int attempt);

    @Query("select distinct s from DefenseSession s join s.committee m where m.member.id = :memberId order by s.scheduledAt asc")
    List<DefenseSession> findByCommitteeMember(@Param("memberId") UUID memberId);

    /** Defenses starting in [earliestStart, end): candidates for room / committee / parallelism checks. */
    List<DefenseSession> findByScheduledAtGreaterThanEqualAndScheduledAtLessThan(Instant earliestStart, Instant end);

    @Query("select distinct s from DefenseSession s join s.committee m where m.member.id in :memberIds "
            + "and s.scheduledAt < :end and s.scheduledAt >= :earliestStart")
    List<DefenseSession> findCommitteeCandidatesOverlapping(@Param("memberIds") Collection<UUID> memberIds,
                                                            @Param("earliestStart") Instant earliestStart,
                                                            @Param("end") Instant end);

    @Query("select s from DefenseSession s where s.group.id = :groupId and s.scheduledAt < :end and s.scheduledAt >= :earliestStart")
    List<DefenseSession> findGroupCandidatesOverlapping(@Param("groupId") UUID groupId,
                                                        @Param("earliestStart") Instant earliestStart,
                                                        @Param("end") Instant end);

    default boolean hasOverlappingForGroup(UUID groupId, Instant startTime, Instant endTime) {
        if (groupId == null) return false;
        Instant earliestStart = startTime.minus(java.time.Duration.ofHours(24));
        return findGroupCandidatesOverlapping(groupId, earliestStart, endTime)
                .stream().anyMatch(s -> s.endsAt().isAfter(startTime));
    }

    default boolean hasOverlappingForCommitteeMembers(Collection<UUID> memberIds, Instant startTime, Instant endTime) {
        if (memberIds == null || memberIds.isEmpty()) return false;
        Instant earliestStart = startTime.minus(java.time.Duration.ofHours(24));
        return findCommitteeCandidatesOverlapping(memberIds, earliestStart, endTime)
                .stream().anyMatch(s -> s.endsAt().isAfter(startTime));
    }

    default boolean hasOverlappingForCommitteeMember(UUID memberId, Instant startTime, Instant endTime) {
        if (memberId == null) return false;
        return hasOverlappingForCommitteeMembers(List.of(memberId), startTime, endTime);
    }
}
