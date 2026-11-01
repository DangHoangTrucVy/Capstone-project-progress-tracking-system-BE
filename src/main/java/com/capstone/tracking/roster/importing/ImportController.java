package com.capstone.tracking.roster.importing;

import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@Tag(name = "Imports", description = "Student list and eligibility: preview then confirm (Admin); phone is optional")
@SecurityRequirement(name = "bearerAuth")
public class ImportController {

    private final StudentImportService students;
    private final EligibilityImportService eligibility;
    @Operation(summary = "Check a student list file without writing it")
    @PostMapping(value = "/semesters/{semester}/imports/students/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport previewStudents(@PathVariable String semester, @RequestParam("file") MultipartFile file,
                                        @AuthenticationPrincipal User currentUser) throws IOException {
        return students.process(semester, file.getOriginalFilename(), file.getBytes(), false, currentUser);
    }

    @Operation(summary = "Import students (MSSV, Họ tên, Email, Kỳ=8, Ngành; optional Số điện thoại, Điều kiện, Lý do)")
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


}
