package com.capstone.tracking.meeting.dto;

import java.util.Map;

/** Matches API-008's response shape. */
public record MinuteGenerateResponse(String minuteDraft, Map<String, String> generatedSections) {
}
