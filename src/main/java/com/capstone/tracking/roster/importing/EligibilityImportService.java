package com.capstone.tracking.roster.importing;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.EligibilityService;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.ImportBatch;
import com.capstone.tracking.roster.ImportBatchRepository;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.roster.importing.ImportReport.RowError;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Line;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Table;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The training department's file of students who are not (or are) eligible (5.1, 5.2). Columns: student code,
 * optional subject code (blank = every subject of the student this semester), optional eligibility (blank = not
 * eligible) and reason. Rows of a finalized scope are kept apart, not written, and the Moderator is told (4.1).
 */
@Service
@RequiredArgsConstructor
public class EligibilityImportService {

    private static final Set<String> NO = Set.of("khong du", "khong du dieu kien", "khong", "no", "false", "0",
            "ineligible", "not eligible", "x");
    private static final Set<String> YES = Set.of("du", "du dieu kien", "co", "yes", "true", "1", "eligible");

    private final UserRepository users;
    private final SemesterStudentRepository semesterStudents;
    private final EnrollmentRepository enrollments;
    private final SubjectRepository subjects;
    private final ImportBatchRepository batches;
    private final EligibilityService eligibility;
    private final Access access;
    private final Notifier notifier;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    @Transactional
    public ImportReport process(String semesterCode, String fileName, byte[] data, boolean commit, User actor) {
        access.requireAdmin(actor);
        access.requireModerator(semesterCode, actor);
        Table table = SpreadsheetReader.read(data);
        if (!table.has(SpreadsheetReader.CODE)) {
            throw new BadRequestException("The file is missing the column: Mã số sinh viên");
        }
        List<RowError> errors = new ArrayList<>();
        List<RowError> blocked = new ArrayList<>();
        List<EligibilityService.Change> changes = new ArrayList<>();
        int unchanged = 0;
        for (Line line : table.lines()) {
            String code = line.get(SpreadsheetReader.CODE);
            if (code == null) {
                errors.add(new RowError(line.rowNumber(), null, "Thiếu mã số sinh viên"));
                continue;
            }
            Eligibility value = parse(line.get(SpreadsheetReader.ELIGIBILITY));
            if (value == null) {
                errors.add(new RowError(line.rowNumber(), code, "Sai giá trị điều kiện: " + line.get(SpreadsheetReader.ELIGIBILITY)));
                continue;
            }
            User user = users.findByStudentCodeIgnoreCase(code).orElse(null);
            SemesterStudent ss = user == null ? null
                    : semesterStudents.findBySemesterCodeAndUserId(semesterCode, user.getId()).orElse(null);
            if (ss == null) {
                errors.add(new RowError(line.rowNumber(), code, "Không có trong danh sách sinh viên của học kỳ"));
                continue;
            }
            List<Enrollment> targets;
            String subjectCode = line.get(SpreadsheetReader.SUBJECT);
            if (subjectCode != null) {
                Subject subject = subjects.findBySemesterCodeAndCodeIgnoreCase(semesterCode, subjectCode).orElse(null);
                if (subject == null) {
                    errors.add(new RowError(line.rowNumber(), code, "Không có môn " + subjectCode + " trong học kỳ"));
                    continue;
                }
                targets = enrollments.findBySubjectIdAndUserId(subject.getId(), user.getId()).map(List::of).orElse(List.of());
                if (targets.isEmpty()) {
                    errors.add(new RowError(line.rowNumber(), code, "Sinh viên không thuộc môn " + subjectCode));
                    continue;
                }
            } else {
                targets = enrollments.findBySemesterStudentId(ss.getId());
                if (targets.isEmpty()) {
                    errors.add(new RowError(line.rowNumber(), code, "Sinh viên chưa thuộc môn nào của học kỳ"));
                    continue;
                }
            }
            String reason = line.get(SpreadsheetReader.REASON);
            for (Enrollment e : targets) {
                if (e.getEligibility() == value && Objects.equals(reason, e.getEligibilityReason())) {
                    unchanged++;
                } else {
                    changes.add(new EligibilityService.Change(e.getId(), value, reason));
                }
            }
        }
        if (!commit) {
            return new ImportReport(false, null, 0, changes.size(), unchanged, errors.size(), errors, List.of(), blocked);
        }
        eligibility.apply(changes, actor);
        if (!blocked.isEmpty()) {
            notifier.publish(DomainEvent.of(DomainEventType.FINALIZED_SCOPE_IMPORT_BLOCKED,
                    blocked.size() + " dòng trong file điều kiện thuộc danh sách đã chốt và chưa được ghi. "
                            + "Mở lại danh sách nếu cần cập nhật.", notifier.coordinators(semesterCode)).by(actor.getId()));
        }
        ImportBatch batch = batches.save(ImportBatch.builder()
                .kind(ImportBatch.Kind.ELIGIBILITY)
                .semesterCode(semesterCode)
                .fileName(fileName)
                .importedBy(actor.getId())
                .importedAt(Instant.now())
                .updatedCount(changes.size())
                .errorCount(errors.size())
                .errorsJson(json(Map.of("errors", errors, "blockedFinalized", blocked)))
                .build());
        audit.record("ImportBatch", batch.getId(), AuditAction.IMPORT_ELIGIBILITY, actor, semesterCode,
                Map.of("file", Objects.toString(fileName, ""), "changes", changes.size(), "errors", errors.size(),
                        "blockedFinalized", blocked.size()));
        return new ImportReport(true, batch.getId(), 0, changes.size(), unchanged, errors.size(), errors, List.of(), blocked);
    }

    private static Eligibility parse(String text) {
        if (text == null) {
            return Eligibility.INELIGIBLE;
        }
        String v = SpreadsheetReader.fold(text);
        if (NO.contains(v)) {
            return Eligibility.INELIGIBLE;
        }
        if (YES.contains(v)) {
            return Eligibility.ELIGIBLE;
        }
        return v.equals("chua ket luan") || v.equals("undecided") ? Eligibility.UNDECIDED : null;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
