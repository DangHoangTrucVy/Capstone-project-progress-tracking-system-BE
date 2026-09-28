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

    @Query("select distinct s from ReviewSession s join s.panel m where m.reviewer.id = :reviewerId order by s.scheduledAt asc")
    List<ReviewSession> findByReviewer(@Param("reviewerId") UUID reviewerId);

    /** Sessions of any of these reviewers overlapping [start, end): a reviewer cannot sit two panels at once. */
    @Query("select distinct s from ReviewSession s join s.panel m where m.reviewer.id in :reviewerIds "
            + "and s.scheduledAt < :end and s.scheduledAt >= :earliestStart")
    List<ReviewSession> findReviewerCandidatesOverlapping(@Param("reviewerIds") Collection<UUID> reviewerIds,
                                                          @Param("earliestStart") Instant earliestStart,
                                                          @Param("end") Instant end);
}
