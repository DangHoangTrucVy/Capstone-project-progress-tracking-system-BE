package com.capstone.tracking.team;

/**
 * What students see of a group (6.3). OPEN: takes applications. FULL: at the maximum size. LOCKED: the Leader locked
 * it, or the scope is past its deadline (a group never returns to Open after the deadline, even when it shrinks).
 * DISSOLVED: no longer exists. {@code full} and {@code locked} are reported separately as well, since a group can be both.
 */
public enum GroupState {
    OPEN,
    FULL,
    LOCKED,
    DISSOLVED
}
