package com.capstone.tracking.meeting.dto;

import com.capstone.tracking.meeting.RequirementStatus;

import java.util.UUID;

/** Both fields are optional; the service applies whichever is present. */
public record RequirementUpdateRequest(RequirementStatus status, UUID assignedTo) {
}
