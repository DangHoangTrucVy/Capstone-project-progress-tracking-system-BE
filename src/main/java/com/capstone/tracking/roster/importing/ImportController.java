package com.capstone.tracking.roster.importing;

import com.capstone.tracking.roster.ImportBatch;
import com.capstone.tracking.roster.ImportBatchRepository;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importing the training department's lists (4.1, 4.2). Each import has a preview call that only checks the file
 * (new / updated / rejected rows with reasons) and a confirm call that writes it; files may be imported many times.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Imports", description = "Student list, ineligibility file and lecturer list: preview, then confirm (Admin)")
@SecurityRequirement(name = "bearerAuth")
public class ImportController {

    private final StudentImportService students;
    private final EligibilityImportService eligibility;
    private final LecturerImportService lecturers;
    private final ImportBatchRepository batches;
    private final Access access;

    public record BatchResponse(UUID id, ImportBatch.Kind kind, String semesterCode, String fileName, UUID importedBy,
                                Instant importedAt, int createdCount, int updatedCount, int errorCount, String errorsJson) {
        static BatchResponse from(ImportBatch b) {
            return new BatchResponse(b.getId(), b.getKind(), b.getSemesterCode(), b.getFileName(), b.getImportedBy(),
                    b.getImportedAt(), b.getCreatedCount(), b.getUpdatedCount(), b.getErrorCount(), b.getErrorsJson());
        }
    }

    @Operation(summary = "Check a student list file without writing it")
    @PostMapping(value = "/semesters/{semester}/imports/students/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport previewStudents(@PathVariable String semester, @RequestParam("file") MultipartFile file,
                                        @AuthenticationPrincipal User currentUser) throws IOException {
        return students.process(semester, file.getOriginalFilename(), file.getBytes(), false, currentUser);
    }

    @Operation(summary = "Import the student list (columns: MSSV, Họ tên, Email, Số điện thoại, Kỳ, Ngành)")
    @PostMapping(value = "/semesters/{semester}/imports/students", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport importStudents(@PathVariable String semester, @RequestParam("file") MultipartFile file,
                                       @AuthenticationPrincipal User currentUser) throws IOException {
        return students.process(semester, file.getOriginalFilename(), file.getBytes(), true, currentUser);
    }

    @Operation(summary = "Check an eligibility file without writing it")
    @PostMapping(value = "/semesters/{semester}/imports/eligibility/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport previewEligibility(@PathVariable String semester, @RequestParam("file") MultipartFile file,
                                           @AuthenticationPrincipal User currentUser) throws IOException {
        return eligibility.process(semester, file.getOriginalFilename(), file.getBytes(), false, currentUser);
    }

    @Operation(summary = "Import the eligibility file (columns: MSSV, Môn (optional), Điều kiện (optional), Lý do)")
    @PostMapping(value = "/semesters/{semester}/imports/eligibility", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport importEligibility(@PathVariable String semester, @RequestParam("file") MultipartFile file,
                                          @AuthenticationPrincipal User currentUser) throws IOException {
        return eligibility.process(semester, file.getOriginalFilename(), file.getBytes(), true, currentUser);
    }

    @Operation(summary = "Check a lecturer list file without writing it")
    @PostMapping(value = "/imports/lecturers/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport previewLecturers(@RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal User currentUser) throws IOException {
        return lecturers.process(file.getOriginalFilename(), file.getBytes(), false, currentUser);
    }

    @Operation(summary = "Import the lecturer list (columns: Họ tên, Email)")
    @PostMapping(value = "/imports/lecturers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport importLecturers(@RequestParam("file") MultipartFile file,
                                        @AuthenticationPrincipal User currentUser) throws IOException {
        return lecturers.process(file.getOriginalFilename(), file.getBytes(), true, currentUser);
    }

    @Operation(summary = "Import history of a semester: who, when, which file, how many rows")
    @GetMapping("/semesters/{semester}/imports")
    public List<BatchResponse> history(@PathVariable String semester, @AuthenticationPrincipal User currentUser) {
        access.requireModerator(semester, currentUser);
        return batches.findBySemesterCodeOrderByImportedAtDesc(semester).stream().map(BatchResponse::from).toList();
    }

    @Operation(summary = "Lecturer list import history (Admin)")
    @GetMapping("/imports/lecturers")
    public List<BatchResponse> lecturerHistory(@AuthenticationPrincipal User currentUser) {
        access.requireAdmin(currentUser);
        return batches.findByKindOrderByImportedAtDesc(ImportBatch.Kind.LECTURERS).stream().map(BatchResponse::from).toList();
    }
}
