package com.capstone.tracking.eligibility;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.common.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** YC03/YC04: capstone eligibility. Admin plays the training department. */
@RestController
@RequestMapping("/api/v1/eligibility")
@RequiredArgsConstructor
@Tag(name = "Eligibility", description = "Student list import and the not-eligible flag")
@SecurityRequirement(name = "bearerAuth")
public class EligibilityController {

    private final EligibilityService eligibilityService;
    private final ObjectMapper objectMapper;

    /** Documents the request shape in Swagger; a bare array of students is accepted too. */
    public record ImportRequest(@NotEmpty List<EligibilityService.Entry> students) {}

    public record FlagRequest(@NotNull Boolean eligible, String reason) {}

    public record MyEligibility(boolean eligible, String reason) {}

    /**
     * Body: {@code {"students":[{"email":"...","fullName":"...","studentCode":"...","eligible":true,"reason":"..."}]}}
     * or the bare array {@code [...]}. {@code email} is required unless the MSSV belongs to an existing account.
     */
    @Operation(summary = "Import the eligible student list (JSON)")
    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @io.swagger.v3.oas.annotations.media.Content(
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ImportRequest.class)))
    public EligibilityService.ImportResult importList(@RequestBody JsonNode body) {
        JsonNode list = body != null && body.isArray() ? body : body == null ? null : body.get("students");
        if (list == null || !list.isArray()) {
            throw new BadRequestException("Send {\"students\": [...]} or an array of students");
        }
        List<EligibilityService.Entry> entries = new ArrayList<>();
        for (JsonNode item : list) {
            if (!item.isObject()) {
                throw new BadRequestException("Each student must be an object with at least an email");
            }
            try {
                entries.add(objectMapper.treeToValue(item, EligibilityService.Entry.class));
            } catch (JsonProcessingException e) {
                throw new BadRequestException("Invalid student " + item + ": " + e.getOriginalMessage());
            }
        }
        return eligibilityService.importList(entries);
    }

    /** YC03: the same import from the training department's CSV file (email, ho_ten, du_dieu_kien, ly_do). */
    @Operation(summary = "Import the eligible student list from a file (.csv, .xlsx, .xls)")
    @PostMapping(value = "/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public EligibilityService.ImportResult importFile(@RequestPart("file") MultipartFile file) throws IOException {
        return eligibilityService.importCsv(file.getBytes());
    }

    /** The students the import created/updated, with their MSSV and eligibility; filter by eligible and keyword. */
    @Operation(summary = "List the imported students (detail, paged)")
    @GetMapping({"/students", "/imported"})
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> imported(@RequestParam(required = false) Boolean eligible,
                                       @RequestParam(required = false) String q,
                                       @ParameterObject Pageable pageable) {
        return eligibilityService.listImported(eligible, q, pageable).map(UserResponse::from);
    }

    @Operation(summary = "Mark a student eligible or not eligible")
    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse setEligibility(@PathVariable UUID userId, @Valid @RequestBody FlagRequest request) {
        return UserResponse.from(eligibilityService.setEligibility(userId, request.eligible(), request.reason()));
    }

    @Operation(summary = "List students marked not eligible")
    @GetMapping("/ineligible")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<UserResponse> ineligible(@ParameterObject Pageable pageable) {
        return eligibilityService.listIneligible(pageable).map(UserResponse::from);
    }

    /** The signed-in student's own status and the reason, even when flagged. */
    @Operation(summary = "My eligibility status")
    @GetMapping("/me")
    public MyEligibility mine(@AuthenticationPrincipal User currentUser) {
        return new MyEligibility(currentUser.isEligible(), currentUser.getIneligibleReason());
    }
}
