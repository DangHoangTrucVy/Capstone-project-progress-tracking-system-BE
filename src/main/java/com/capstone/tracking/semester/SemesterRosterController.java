package com.capstone.tracking.semester;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.RosterService;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
import com.capstone.tracking.subject.SubjectService;
import com.capstone.tracking.subject.dto.ScopeResponse;
import com.capstone.tracking.team.GroupState;
import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Semester-first endpoints: the UI need not choose a subject because the only subject is Capstone. */
@RestController
@RequestMapping("/api/v1/semesters/{semester}")
@RequiredArgsConstructor
@Tag(name = "Student list")
@SecurityRequirement(name = "bearerAuth")
public class SemesterRosterController {
    private final Access access;
    private final SubjectRepository subjects;
    private final SubjectService subjectService;
    private final RosterService roster;

    @Operation(summary = "Student roster by semester; filter major/eligibility/hasGroup, search code/name/email, paginate")
    @GetMapping("/students")
    public Page<RosterService.StudentRow> students(@PathVariable String semester,
            @RequestParam(required = false) UUID majorId, @RequestParam(required = false) Boolean hasGroup,
            @RequestParam(required = false) Eligibility eligibility, @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable, @AuthenticationPrincipal User actor) {
        checkPage(pageable);
        return roster.students(capstone(semester, actor).getId(), majorId, hasGroup, eligibility, q, pageable, actor);
    }

    @Operation(summary = "Paginated groups by semester; maxSize=2 lists groups below 3, finalized filters group finalization")
    @GetMapping("/groups")
    public Page<GroupResponse> groups(@PathVariable String semester, @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) GroupState state, @RequestParam(required = false) Integer maxSize,
            @RequestParam(required = false) Boolean finalized, @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable, @AuthenticationPrincipal User actor) {
        checkPage(pageable);
        List<GroupResponse> all = roster.groups(capstone(semester, actor).getId(), majorId, state, maxSize, q, actor)
                .stream().filter(g -> finalized == null || g.finalized() == finalized)
                .sorted(java.util.Comparator.comparing(GroupResponse::majorCode).thenComparing(GroupResponse::code)).toList();
        return new PageImpl<>(all.stream().skip(pageable.getOffset()).limit(pageable.getPageSize()).toList(), pageable, all.size());
    }

    @Operation(summary = "Formation scopes for the semester, one per major (Admin or assigned Moderator)")
    @GetMapping("/scopes")
    public List<ScopeResponse> scopes(@PathVariable String semester, @RequestParam(required = false) UUID majorId,
                                     @AuthenticationPrincipal User actor) {
        return subjectService.toResponse(capstone(semester, actor)).scopes().stream()
                .filter(s -> majorId == null || s.majorId().equals(majorId)).toList();
    }

    private Subject capstone(String semester, User actor) {
        access.requireModerator(semester, actor);
        return subjects.findBySemesterCodeAndCodeIgnoreCase(semester, Subject.CAPSTONE_CODE)
                .orElseThrow(() -> ResourceNotFoundException.of("Capstone configuration", semester));
    }

    private static void checkPage(Pageable pageable) {
        if (pageable.getPageSize() > 100) throw new BadRequestException("Page size must not exceed 100");
    }
}
