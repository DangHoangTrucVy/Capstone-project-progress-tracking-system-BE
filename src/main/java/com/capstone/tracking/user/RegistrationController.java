package com.capstone.tracking.user;

import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/registrations")
@RequiredArgsConstructor
@Tag(name = "Registrations")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class RegistrationController {

    private final RegistrationService registrationService;

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

    @Operation(summary = "Student sign-ups with a personal email (default: waiting for approval)")
    @GetMapping
    public Page<UserResponse> list(@RequestParam(defaultValue = "PENDING_APPROVAL") UserStatus status,
                                   @ParameterObject Pageable pageable) {
        return registrationService.list(status, pageable).map(UserResponse::from);
    }

    @Operation(summary = "Approve a sign-up: the student can sign in")
    @PostMapping("/{id}/approve")
    public UserResponse approve(@PathVariable UUID id) {
        return UserResponse.from(registrationService.approve(id));
    }

    @Operation(summary = "Reject a sign-up with a reason")
    @PostMapping("/{id}/reject")
    public UserResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        return UserResponse.from(registrationService.reject(id, request.reason()));
    }
}
