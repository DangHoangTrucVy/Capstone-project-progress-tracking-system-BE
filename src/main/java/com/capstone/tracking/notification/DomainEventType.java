package com.capstone.tracking.notification;

/**
 * Notification kinds (8.3). Every notification is shown on the web (bell) and, unless {@code emailed} is false,
 * emailed to the address on the imported list.
 */
public enum DomainEventType {
    // ---- Apply / Invite (6.2)
    APPLICATION_RECEIVED(true),
    APPLICATION_ACCEPTED(true),
    APPLICATION_REJECTED(true),
    INVITE_RECEIVED(true),
    INVITE_ACCEPTED(true),
    INVITE_DECLINED(true),
    // ---- members and Leader (6.4, 6.5)
    MEMBER_JOINED(false),
    MEMBER_LEFT(true),
    MEMBER_KICKED(true),
    LEAVE_REQUESTED(true),
    LEAVE_DECIDED(true),
    COMPLAINT_RECEIVED(true),
    LEADER_TRANSFER_REQUESTED(true),
    LEADER_TRANSFER_ANSWERED(true),
    LEADER_CHANGED(true),
    /** The system picked this student as the new Leader. */
    BECAME_LEADER(true),
    GROUP_DISSOLVED(true),
    // ---- eligibility (5.2)
    ELIGIBILITY_CHANGED(true),
    REMOVED_INELIGIBLE(true),
    // ---- Moderator coordination (7.1)
    ADDED_BY_MODERATOR(true),
    MOVED_BY_MODERATOR(true),
    REMOVED_BY_MODERATOR(true),
    // ---- scope phases and deadlines (3.4, 3.5)
    SCOPE_OPENED(true),
    SCOPE_LOCKED(true),
    SCOPE_EXTENDED(true),
    SCOPE_FINALIZED(true),
    SCOPE_REOPENED(true),
    DEADLINE_CHANGED(true),
    // ---- to the Moderator
    SCOPE_CANNOT_OPEN(true),
    COORDINATION_NEEDED(true),
    EXPORT_SMALL_GROUPS(true),
    FINALIZED_SCOPE_IMPORT_BLOCKED(true),
    LEADER_CHANGED_AFTER_FINALIZE(true),
    MODERATOR_ASSIGNED(true),
    // ---- v3.0: rooms, finalize requests and semester phases (6.4, 7, 8.3)
    ROOM_FINALIZE_REQUESTED(true),
    ROOM_FINALIZE_DECIDED(true),
    ROOM_CHANGED_BY_MODERATOR(true),
    FORMING_STARTED(true),
    SUPPLEMENT_OPENED(true),
    FORMATION_CLOSED(true),
    /** Supplement phase: rooms below the maximum hear who still needs a group, and those students hear it too. */
    STUDENTS_NEED_GROUP(true),
    /** Supplement phase: a student accepted an invite; the Moderator is told. */
    SUPPLEMENT_JOINED(true);

    private final boolean emailed;

    DomainEventType(boolean emailed) {
        this.emailed = emailed;
    }

    public boolean isEmailed() {
        return emailed;
    }
}
