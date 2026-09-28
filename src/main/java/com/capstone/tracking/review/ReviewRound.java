package com.capstone.tracking.review;

/** Giai đoạn 5: progress reviews of the semester. */
public enum ReviewRound {
    /** Tuần 3-4: first-leg review. */
    REVIEW_1("Review 1"),
    /** Tuần 7: mid-term review, usually cloned from Review 1's schedule. */
    REVIEW_2("Review 2"),
    /** Tuần 14: closed council (Hội đồng kín) — 3 reviewers, one of them chair — that sorts groups for defense. */
    REVIEW_3("Review 3 (Hội đồng kín)");

    private final String label;

    ReviewRound(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
