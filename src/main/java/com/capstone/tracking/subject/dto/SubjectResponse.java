package com.capstone.tracking.subject.dto;

import com.capstone.tracking.subject.Subject;
import java.util.List;
import java.util.UUID;

public record SubjectResponse(
        UUID id,
        String semesterCode,
        String code,
        String name,
        int targetTerm,
        int minGroupSize,
        int maxGroupSize,
        int requestTtlMinutes,
        int minDeadlineNoticeMinutes,
        List<ScopeResponse> scopes
) {
    public static SubjectResponse from(Subject s, List<ScopeResponse> scopes, int minDeadlineNoticeMinutes) {
        return new SubjectResponse(s.getId(), s.getSemesterCode(), s.getCode(), s.getName(), s.getTargetTerm(),
                s.getMinGroupSize(), s.getMaxGroupSize(), s.getRequestTtlMinutes(), minDeadlineNoticeMinutes, scopes);
    }
}
