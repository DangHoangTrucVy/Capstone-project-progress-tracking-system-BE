package com.capstone.tracking.meeting.dto;

/**
 * Matches API-008's payload, with blueprint's {@code rawNotes}/{@code notesTranscript} pair
 * collapsed into one field — there's no separate transcript-ingestion pipeline in this codebase.
 * Optional: when omitted, the session's already-recorded {@code rawNotes} is used instead.
 */
public record MinuteGenerateRequest(String notes) {
}
