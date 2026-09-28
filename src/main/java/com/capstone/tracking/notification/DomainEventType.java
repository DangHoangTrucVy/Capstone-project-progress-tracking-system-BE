package com.capstone.tracking.notification;

/** {@code emailed}: besides the in-app notification, the group is emailed (To the leader, CC members + supervisor). */
public enum DomainEventType {
    DOCUMENT_SUBMITTED(false),
    BOOKING_CONFIRMED(false),
    BOOKING_CANCELLED(false),
    PROGRESS_REPORTED(false),
    PROGRESS_FEEDBACK(false),
    TOPIC_PROPOSAL_SUBMITTED(false),
    TOPIC_FORWARDED_TO_COUNCIL(true),
    TOPIC_APPROVED(true),
    TOPIC_REJECTED(true),
    PROPOSAL_ROUND_OPENED(true),
    WARNING_FLAG_RAISED(true),
    WARNING_FLAG_RESOLVED(false),
    REVIEW_SCHEDULED(false),
    REVIEW_RESULT(true),
    DEFENSE_SCHEDULED(true),
    DEFENSE_RESULT(true);

    private final boolean emailed;

    DomainEventType(boolean emailed) {
        this.emailed = emailed;
    }

    public boolean isEmailed() {
        return emailed;
    }
}
