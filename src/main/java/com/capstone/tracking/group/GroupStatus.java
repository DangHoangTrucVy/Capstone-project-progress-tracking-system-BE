package com.capstone.tracking.group;

/** Lifecycle per blueprint.md §8 Data Model: Formed -> Active -> Completed | Failed -> Archived. */
public enum GroupStatus {
    FORMED,
    ACTIVE,
    COMPLETED,
    /** Did not pass the second (last) defense. */
    FAILED,
    ARCHIVED
}
