package com.capstone.tracking.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewSessionRepository extends JpaRepository<ReviewSession, UUID> {

    boolean existsByGroup_Semester(String semester);
    List<ReviewSession> findByGroup_Semester(String semester);

    List<ReviewSession> findByScheduledAtLessThan(Instant end);

    List<ReviewSession> findByGroupIdOrderByScheduledAtAsc(UUID groupId);

    Optional<ReviewSession> findByGroupIdAndRound(UUID groupId, ReviewRound round);

    boolean existsByGroupIdAndRound(UUID groupId, ReviewRound round);

    List<ReviewSession> findByRoundAndGroup_SemesterOrderByScheduledAtAsc(ReviewRound round, String semester);

    List<ReviewSession> findByRoundOrderByScheduledAtAsc(ReviewRound round);

    List<ReviewSession> findByScheduledAtGreaterThanEqualAndScheduledAtLessThan(Instant earliestStart, Instant end);

    @Query("select distinct s from ReviewSession s join s.panel m where m.reviewer.id = :reviewerId order by s.scheduledAt asc")
    List<ReviewSession> findByReviewer(@Param("reviewerId") UUID reviewerId);

    /** Sessions of any of these reviewers overlapping [start, end): a reviewer cannot sit two panels at once. */
    @Query("select distinct s from ReviewSession s join s.panel m where m.reviewer.id in :reviewerIds "
            + "and s.scheduledAt < :end and s.scheduledAt >= :earliestStart")
    List<ReviewSession> findReviewerCandidatesOverlapping(@Param("reviewerIds") Collection<UUID> reviewerIds,
                                                          @Param("earliestStart") Instant earliestStart,
                                                          @Param("end") Instant end);

    @Query("select s from ReviewSession s where s.group.id = :groupId and s.scheduledAt < :end and s.scheduledAt >= :earliestStart")
    List<ReviewSession> findGroupCandidatesOverlapping(@Param("groupId") UUID groupId,
                                                       @Param("earliestStart") Instant earliestStart,
                                                       @Param("end") Instant end);

    default boolean hasOverlappingForGroup(UUID groupId, Instant startTime, Instant endTime) {
        if (groupId == null) return false;
        Instant earliestStart = startTime.minus(java.time.Duration.ofHours(24));
        return findGroupCandidatesOverlapping(groupId, earliestStart, endTime)
                .stream().anyMatch(s -> s.endsAt().isAfter(startTime));
    }

    default boolean hasOverlappingForReviewers(Collection<UUID> reviewerIds, Instant startTime, Instant endTime) {
        if (reviewerIds == null || reviewerIds.isEmpty()) return false;
        Instant earliestStart = startTime.minus(java.time.Duration.ofHours(24));
        return findReviewerCandidatesOverlapping(reviewerIds, earliestStart, endTime)
                .stream().anyMatch(s -> s.endsAt().isAfter(startTime));
    }

    default boolean hasOverlappingForReviewer(UUID reviewerId, Instant startTime, Instant endTime) {
        if (reviewerId == null) return false;
        return hasOverlappingForReviewers(List.of(reviewerId), startTime, endTime);
    }
}
