package com.capstone.tracking.user;

/**
 * Lifecycle per blueprint.md §8 Data Model: Active -> Suspended -> Inactive. A student who signs up with a personal
 * email starts as PENDING_APPROVAL until an Admin confirms they are a student of the school (or REJECTED).
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE,
    PENDING_APPROVAL,
    REJECTED
}
