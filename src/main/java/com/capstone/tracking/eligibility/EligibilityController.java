package com.capstone.tracking.eligibility;

import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** YC03/YC04: capstone eligibility. Admin plays the training department. */
@RestController
@RequestMapping("/api/v1/eligibility")
@RequiredArgsConstructor
@Tag(name = "Eligibility", description = "Student list import and the not-eligible flag")
@SecurityRequirement(name = "bearerAuth")
public class EligibilityController {

    private final EligibilityService eligibilityService;

    public record ImportRequest(@NotEmpty List<EligibilityService.Entry> students) {}

    public record FlagRequest(@NotNull Boolean eligible, String reason) {}

    public record MyEligibility(boolean eligible, String reason) {}

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public EligibilityService.ImportResult importList(@Valid @RequestBody ImportRequest request) {
        return eligibilityService.importList(request.students());
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse setEligibility(@PathVariable UUID userId, @Valid @RequestBody FlagRequest request) {
        return UserResponse.from(eligibilityService.setEligibility(userId, request.eligible(), request.reason()));
    }

    @GetMapping("/ineligible")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> ineligible(Pageable pageable) {
        return eligibilityService.listIneligible(pageable).map(UserResponse::from);
    }

    /** The signed-in student's own status and the reason, even when flagged. */
    @GetMapping("/me")
    public MyEligibility mine(@AuthenticationPrincipal User currentUser) {
        return new MyEligibility(currentUser.isEligible(), currentUser.getIneligibleReason());
    }
}
