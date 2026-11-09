package com.capstone.tracking.semester;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.RosterService;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The student tabs of the semester screen (4.3): all students, and those without a group with the reason. */
@RestController
@RequestMapping("/api/v1/semesters/{semester}")
@RequiredArgsConstructor
@Tag(name = "Student list")
@SecurityRequirement(name = "bearerAuth")
public class SemesterRosterController {

    private final RosterService roster;

    @Operation(summary = "Students of the semester; filter major/eligibility/hasGroup, search code/name/email, paginate")
    @GetMapping("/students")
    public Page<RosterService.StudentRow> students(@PathVariable String semester,
            @RequestParam(required = false) UUID majorId, @RequestParam(required = false) Boolean hasGroup,
            @RequestParam(required = false) Eligibility eligibility, @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable, @AuthenticationPrincipal User actor) {
        if (pageable.getPageSize() > 100) {
            throw new BadRequestException("Page size must not exceed 100");
        }
        return roster.students(semester, majorId, hasGroup, eligibility, q, pageable, actor);
    }

    @Operation(summary = "The majors of the semester (from the imported list) with their short code")
    @GetMapping("/majors")
    public List<RosterService.MajorRow> majors(@PathVariable String semester, @AuthenticationPrincipal User actor) {
        return roster.majors(semester, actor);
    }
}
