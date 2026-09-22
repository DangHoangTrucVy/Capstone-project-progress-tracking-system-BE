package com.capstone.tracking.meeting.dto;

/** Optional final working notes, persisted onto the session when the meeting ends. */
public record EndSessionRequest(String rawNotes) {
}
