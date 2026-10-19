package com.capstone.tracking.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import com.capstone.tracking.user.dto.UserCreateRequest;
import com.capstone.tracking.user.dto.UserResponse;
import com.capstone.tracking.user.dto.UserUpdateRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Account management (mục 2): only the Admin. Students and lecturers normally come from the list imports. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User account management")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Create a user account")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        User created = userService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.getId()))
                .body(UserResponse.from(created));
    }

    @Operation(summary = "List accounts (filter by role, search email/name/student code)")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> list(@RequestParam(required = false) Role role,
                                   @RequestParam(required = false) String q,
                                   @ParameterObject Pageable pageable) {
        return userService.list(role, q, pageable).map(UserResponse::from);
    }

    @Operation(summary = "Get a user")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse getById(@PathVariable UUID id) {
        return UserResponse.from(userService.getById(id));
    }

    @Operation(summary = "Update an account (name, email, phone, status)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequest request) {
        return UserResponse.from(userService.update(id, request));
    }
}
