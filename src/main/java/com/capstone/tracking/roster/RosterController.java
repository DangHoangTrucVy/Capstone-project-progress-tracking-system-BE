package com.capstone.tracking.roster;

import com.capstone.tracking.roster.RosterService.StudentRow;
import com.capstone.tracking.roster.RosterService.StudentUpdate;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Student list", description = "Student contact details and eligibility; lists are served by semester")
@SecurityRequirement(name = "bearerAuth")
public class RosterController {

    private final RosterService roster;
    private final EligibilityService eligibility;

    public record EligibilityRequest(@NotNull Eligibility eligibility, @NotBlank @Size(max = 500) String reason) {}

    @Operation(summary = "Edit student contact details in every phase (Admin, assigned Moderator); major and term cannot change")
    @PutMapping("/semester-students/{semesterStudentId}")
    public StudentRow update(@PathVariable UUID semesterStudentId, @Valid @RequestBody StudentUpdate update,
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
