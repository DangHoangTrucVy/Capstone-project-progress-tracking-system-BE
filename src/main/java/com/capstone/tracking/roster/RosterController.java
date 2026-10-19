package com.capstone.tracking.roster;

import com.capstone.tracking.roster.RosterService.StudentRow;
import com.capstone.tracking.roster.RosterService.StudentUpdate;
import com.capstone.tracking.team.GroupState;
import com.capstone.tracking.team.dto.GroupResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Student list", description = "List management by semester, subject and major (4.3) and eligibility (5)")
@SecurityRequirement(name = "bearerAuth")
public class RosterController {

    private final RosterService roster;
    private final EligibilityService eligibility;

    public record EligibilityRequest(@NotNull Eligibility eligibility, @NotBlank @Size(max = 500) String reason) {}

    @Operation(summary = "Students of a subject: filter by major, has-group, eligibility; search code/name/email (Admin, Moderator)")
    @GetMapping("/subjects/{subjectId}/students")
    public Page<StudentRow> students(@PathVariable UUID subjectId,
                                     @RequestParam(required = false) UUID majorId,
                                     @RequestParam(required = false) Boolean hasGroup,
                                     @RequestParam(required = false) Eligibility eligibility,
                                     @RequestParam(required = false) String q,
                                     @ParameterObject Pageable pageable,
                                     @AuthenticationPrincipal User currentUser) {
        return roster.students(subjectId, majorId, hasGroup, eligibility, q, pageable, currentUser);
    }

    @Operation(summary = "Groups of a subject: filter by major, state, size (maxSize=2 lists groups below 3) (Admin, Moderator)")
    @GetMapping("/subjects/{subjectId}/groups")
    public List<GroupResponse> groups(@PathVariable UUID subjectId,
                                      @RequestParam(required = false) UUID majorId,
                                      @RequestParam(required = false) GroupState state,
                                      @RequestParam(required = false) Integer maxSize,
                                      @RequestParam(required = false) String q,
                                      @AuthenticationPrincipal User currentUser) {
        return roster.groups(subjectId, majorId, state, maxSize, q, currentUser);
    }

    @Operation(summary = "Edit a student's details; major and term only before formation starts (Admin, Moderator)")
    @PutMapping("/semester-students/{semesterStudentId}")
    public StudentRow update(@PathVariable UUID semesterStudentId, @RequestBody StudentUpdate update,
                             @AuthenticationPrincipal User currentUser) {
        return roster.update(semesterStudentId, update, currentUser);
    }

    @Operation(summary = "Change a student's eligibility in a subject, with a reason (Moderator)")
    @PutMapping("/enrollments/{enrollmentId}/eligibility")
    public void changeEligibility(@PathVariable UUID enrollmentId, @Valid @RequestBody EligibilityRequest request,
                                  @AuthenticationPrincipal User currentUser) {
        eligibility.change(enrollmentId, request.eligibility(), request.reason(), currentUser);
    }
}
