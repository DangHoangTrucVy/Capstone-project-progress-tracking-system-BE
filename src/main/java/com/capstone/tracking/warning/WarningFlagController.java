package com.capstone.tracking.warning;

import com.capstone.tracking.user.User;
import com.capstone.tracking.warning.dto.WarningFlagRequest;
import com.capstone.tracking.warning.dto.WarningFlagResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Warning Flags", description = "Supervisor warnings on late groups / inactive members (bước 4.4)")
@SecurityRequirement(name = "bearerAuth")
public class WarningFlagController {

    private final WarningFlagService flagService;

    @PostMapping("/api/v1/groups/{groupId}/warning-flags")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public ResponseEntity<WarningFlagResponse> raise(@PathVariable UUID groupId, @Valid @RequestBody WarningFlagRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(flagService.raise(groupId, request, currentUser));
    }

    @GetMapping("/api/v1/groups/{groupId}/warning-flags")
    public List<WarningFlagResponse> list(@PathVariable UUID groupId,
                                          @RequestParam(defaultValue = "false") boolean activeOnly,
                                          @AuthenticationPrincipal User currentUser) {
        return flagService.listByGroup(groupId, activeOnly, currentUser);
    }

    /** Body: {"note": "..."} (optional). */
    @PutMapping("/api/v1/warning-flags/{id}/resolve")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public WarningFlagResponse resolve(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body,
                                       @AuthenticationPrincipal User currentUser) {
        return flagService.resolve(id, body != null ? body.get("note") : null, currentUser);
    }
}
