package com.capstone.tracking.subject;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.RosterSync;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.dto.ScopeResponse;
import com.capstone.tracking.subject.dto.SubjectResponse;
import com.capstone.tracking.team.StudentGroupRepository;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The subjects that need groups in a semester (3.2), created by the Moderator; each major becomes a scope. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubjectService {

    private final SubjectRepository subjects;
    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;
    private final StudentGroupRepository groups;
    private final RosterSync sync;
    private final ScopeRules rules;
    private final Access access;

    public record SubjectRequest(String code, String name, Integer targetTerm, Integer requestTtlHours) {}

    @Transactional
    public Subject create(String semesterCode, SubjectRequest request, User actor) {
        access.requireModerator(semesterCode, actor);
        if (request.code() == null || request.code().isBlank() || request.code().trim().length() > 20) {
            throw new BadRequestException("The subject code must contain 1 to 20 characters");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("The subject name is required");
        }
        if (request.targetTerm() == null || request.targetTerm() < 1 || request.targetTerm() > 12) {
            throw new BadRequestException("The target term must be between 1 and 12");
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (subjects.existsBySemesterCodeAndCodeIgnoreCase(semesterCode, code)) {
            throw new ConflictException("Subject " + code + " already exists in " + semesterCode);
        }
        Subject subject = subjects.saveAndFlush(Subject.builder()
                .semesterCode(semesterCode)
                .code(code)
                .name(request.name().trim())
                .targetTerm(request.targetTerm())
                .requestTtlHours(ttl(request.requestTtlHours(), Subject.DEFAULT_REQUEST_TTL_HOURS))
                .createdBy(actor.getId())
                .build());
        sync.syncSubject(subject);
        return subject;
    }

    @Transactional
    public Subject update(UUID subjectId, SubjectRequest request, User actor) {
        Subject subject = get(subjectId);
        access.requireModerator(subject.getSemesterCode(), actor);
        if (request.name() != null && !request.name().isBlank()) {
            subject.setName(request.name().trim());
        }
        if (request.requestTtlHours() != null) {
            subject.setRequestTtlHours(ttl(request.requestTtlHours(), subject.getRequestTtlHours()));
        }
        if (request.targetTerm() != null && request.targetTerm() != subject.getTargetTerm()) {
            throw new BadRequestException("The target term cannot change; delete the subject and create it again");
        }
        return subject;
    }

    /** Only while every scope is still being prepared: no group exists yet. */
    @Transactional
    public void delete(UUID subjectId, User actor) {
        Subject subject = get(subjectId);
        access.requireModerator(subject.getSemesterCode(), actor);
        if (scopes.existsBySubjectIdAndPhaseNot(subjectId, ScopePhase.PREPARING)) {
            throw new ApiException(HttpStatus.CONFLICT, "SUBJECT_IN_USE",
                    "Group formation has started for this subject; it can no longer be deleted");
        }
        enrollments.deleteAll(enrollments.findBySubjectId(subjectId));
        enrollments.flush();
        scopes.deleteAll(scopes.findBySubjectIdOrderByMajor_CodeAsc(subjectId));
        scopes.flush();
        subjects.delete(subject);
    }

    public Subject get(UUID subjectId) {
        return subjects.findById(subjectId).orElseThrow(() -> ResourceNotFoundException.of("Subject", subjectId));
    }

    public List<SubjectResponse> list(String semesterCode) {
        return subjects.findBySemesterCodeOrderByCodeAsc(semesterCode).stream().map(this::toResponse).toList();
    }

    public SubjectResponse toResponse(Subject subject) {
        return SubjectResponse.from(subject, scopes.findBySubjectIdOrderByMajor_CodeAsc(subject.getId()).stream()
                .map(this::toResponse).toList());
    }

    public ScopeResponse toResponse(FormationScope scope) {
        return ScopeResponse.from(scope, rules.deadline(scope), enrollments.countByScopeId(scope.getId()),
                groups.findActiveByScope(scope.getId()).size());
    }

    public Instant deadline(FormationScope scope) {
        return rules.deadline(scope);
    }

    private static int ttl(Integer hours, int fallback) {
        if (hours == null) {
            return fallback;
        }
        if (hours < 1 || hours > 24 * 14) {
            throw new BadRequestException("The request lifetime must be between 1 hour and 14 days");
        }
        return hours;
    }
}
