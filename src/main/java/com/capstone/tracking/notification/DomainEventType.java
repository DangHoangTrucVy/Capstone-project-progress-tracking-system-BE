package com.capstone.tracking.notification;

/** {@code emailed}: besides the in-app notification, the group is emailed (To the leader, CC members + supervisor). */
public enum DomainEventType {
    DOCUMENT_SUBMITTED(false),
    DOCUMENT_FEEDBACK(true),
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
    DEFENSE_RESULT(true),
    JOIN_APPLICATION_RECEIVED(false),
    JOIN_APPLICATION_REJECTED(false),
    JOIN_INVITE_RECEIVED(false),
    JOIN_INVITE_DECLINED(false),
    MEMBER_JOINED(false),
    MEMBER_LEFT(false),
    MEMBER_REMOVED(false),
    LEAVE_REQUESTED(false),
    LEAVE_DECIDED(false),
    ROSTER_SUBMITTED(false),
    ROSTER_REVIEWED(false),
    ROSTER_CHANGE_REPORTED(false),
    /** Personal (no group): the student's capstone eligibility flag was set or lifted (YC03). */
    ELIGIBILITY_CHANGED(false);

    private final boolean emailed;

    DomainEventType(boolean emailed) {
        this.emailed = emailed;
    }

    public boolean isEmailed() {
        return emailed;
    }
}
