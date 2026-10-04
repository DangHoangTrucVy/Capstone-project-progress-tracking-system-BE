package com.capstone.tracking.group;

/**
 * PENDING is the only open state. An Apply ends APPROVED (the leader accepted it and sent an Invite; the student is
 * NOT a member yet, YC10), REJECTED, WITHDRAWN or EXPIRED. An Invite ends ACCEPTED (the moment of joining, YC11),
 * REJECTED (declined), WITHDRAWN (revoked by the leader) or EXPIRED. CANCELLED: the student joined a group, so every
 * other open request of theirs was cancelled by the system (YC13).
 */
public enum JoinRequestStatus {
    PENDING,
    APPROVED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    EXPIRED,
    CANCELLED
}
