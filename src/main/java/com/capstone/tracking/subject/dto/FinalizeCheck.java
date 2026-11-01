package com.capstone.tracking.subject.dto;

import java.util.List;
import java.util.UUID;

/**
 * Whether the list can be finalized (7.2). Blockers stop it: groups below the minimum size, unhandled leave requests
 * or complaints. Warnings do not: eligible or undecided students still without a group.
 */
public record FinalizeCheck(
        boolean canFinalize,
        List<String> blockers,
        List<String> warnings,
        List<GroupRef> groupsUnderMinimum,
        long openLeaveRequests,
        long studentsWithoutGroup
) {
    public record GroupRef(UUID id, String code, String name, long size) {}
}
