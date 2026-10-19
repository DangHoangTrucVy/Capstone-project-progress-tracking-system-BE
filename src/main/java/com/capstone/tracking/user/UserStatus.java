package com.capstone.tracking.user;

/** Account lifecycle: an Admin suspends or deactivates an account; neither may sign in. */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE
}
