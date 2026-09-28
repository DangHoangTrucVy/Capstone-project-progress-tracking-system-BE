package com.capstone.tracking.proposal;

import java.time.Duration;

/** The regulation's numbers for topic approval (Giai đoạn 2). */
public final class ProposalPolicy {

    /** At most 4 council reviews per group. */
    public static final int MAX_ROUNDS = 4;

    /** Round 1: the Leader sends the list of 10 candidate topics. */
    public static final int FIRST_ROUND_TOPIC_COUNT = 10;

    /** Later rounds re-submit the revised topic(s), still at most 10. */
    public static final int MAX_TOPICS_PER_SUBMISSION = 10;

    /** Council deadline: 14 days for round 1, 10 days for rounds 2-4. */
    public static Duration councilReviewPeriod(int round) {
        return Duration.ofDays(round == 1 ? 14 : 10);
    }

    /** Default length of the submission window an Admin opens for rounds 2-4. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(10);

    private ProposalPolicy() {
    }
}
