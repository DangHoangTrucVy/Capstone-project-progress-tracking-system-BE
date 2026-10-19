package com.capstone.tracking.major;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Majors (3.3). Usually created by the student list import from its "Ngành" column; the Admin may add or rename. */
@RestController
@RequestMapping("/api/v1/majors")
@RequiredArgsConstructor
@Tag(name = "Majors", description = "Students' majors; groups are formed within one major")
@SecurityRequirement(name = "bearerAuth")
public class MajorController {

    private final MajorRepository majors;

    public record MajorRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 255) String name) {}

    public record MajorResponse(UUID id, String code, String name) {
        static MajorResponse from(Major m) {
            return new MajorResponse(m.getId(), m.getCode(), m.getName());
        }
    }

    @Operation(summary = "List majors")
    @GetMapping
    public List<MajorResponse> list() {
        return majors.findAllByOrderByCodeAsc().stream().map(MajorResponse::from).toList();
    }

    @Operation(summary = "Create a major (Admin)")
    @ApiResponse(responseCode = "201", description = "Created")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<MajorResponse> create(@Valid @RequestBody MajorRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (majors.existsByCodeIgnoreCase(code) || majors.findByNameIgnoreCase(request.name().trim()).isPresent()) {
            throw new ConflictException("A major with this code or name already exists");
        }
        Major saved = majors.save(Major.builder().code(code).name(request.name().trim()).build());
        return ResponseEntity.created(URI.create("/api/v1/majors/" + saved.getId())).body(MajorResponse.from(saved));
    }

    @Operation(summary = "Rename a major (Admin)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public MajorResponse update(@PathVariable UUID id, @Valid @RequestBody MajorRequest request) {
        Major major = majors.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Major", id));
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        majors.findByCodeIgnoreCase(code).filter(m -> !m.getId().equals(id)).ifPresent(m -> {
            throw new BadRequestException("Code " + code + " belongs to another major");
        });
        major.setCode(code);
        major.setName(request.name().trim());
        return MajorResponse.from(major);
    }
}
