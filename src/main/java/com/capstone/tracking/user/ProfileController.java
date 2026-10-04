package com.capstone.tracking.user;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** YC22: a student's recruiting profile, shown to a group only while they have an open Apply to it. */
@RestController
@RequestMapping("/api/v1/me/profile")
@RequiredArgsConstructor
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final UserRepository userRepository;

    public record ProfileRequest(@Size(max = 1000) String bio, @Size(max = 500) String skills) {}

    @Operation(summary = "Get my recruiting profile")
    @GetMapping
    public UserResponse get(@AuthenticationPrincipal User currentUser) {
        return UserResponse.from(currentUser);
    }

    @Operation(summary = "Update my recruiting profile")
    @PutMapping
    @Transactional
    public UserResponse update(@AuthenticationPrincipal User currentUser, @Valid @RequestBody ProfileRequest request) {
        User user = userRepository.findById(currentUser.getId()).orElseThrow();
        user.setBio(request.bio() == null || request.bio().isBlank() ? null : request.bio().trim());
        user.setSkills(request.skills() == null || request.skills().isBlank() ? null : request.skills().trim());
        return UserResponse.from(user);
    }
}
