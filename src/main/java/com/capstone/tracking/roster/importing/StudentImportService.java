package com.capstone.tracking.roster.importing;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.major.Major;
import com.capstone.tracking.major.MajorRepository;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.ImportBatch;
import com.capstone.tracking.roster.ImportBatchRepository;
import com.capstone.tracking.roster.RosterSync;
import com.capstone.tracking.roster.SemesterStudent;
import com.capstone.tracking.roster.SemesterStudentRepository;
import com.capstone.tracking.roster.importing.ImportReport.RowError;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Line;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Table;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The semester's student list from the training department (4.1). Rows are matched by student code + semester, so
 * importing again never duplicates anyone; students missing from a later file are left as they are. Once the semester
 * has left OPENING, a row may only update contact details (name, email, phone): new students are rejected. A major
 * never changes (3.3). The same call previews (nothing written) or, confirmed, writes.
 */
@Service
@RequiredArgsConstructor
public class StudentImportService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 .\\-]{6,20}$");

    private final UserRepository users;
    private final MajorRepository majors;
    private final SemesterStudentRepository semesterStudents;
    private final EnrollmentRepository enrollments;
    private final ImportBatchRepository batches;
    private final RosterSync sync;
    private final Access access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final com.capstone.tracking.common.Locks locks;
    private final com.capstone.tracking.roster.EligibilityService eligibilityService;

    private record Row(Line line, String code, String name, String email, String phone, int term, String majorKey,
                       Major major, User user, SemesterStudent existing, ImportedEligibility decision) {}

    @Transactional
    public ImportReport process(String semesterCode, String fileName, byte[] data, boolean commit, User actor) {
        access.requireAdmin(actor);
        access.requireModerator(semesterCode, actor);
        Semester semester = locks.writeSemester(semesterCode);
        Table table = SpreadsheetReader.read(data);
        List<String> missing = new ArrayList<>();
        for (String[] col : new String[][]{{SpreadsheetReader.CODE, "Mã số sinh viên"}, {SpreadsheetReader.NAME, "Họ tên"},
                {SpreadsheetReader.EMAIL, "Email"},
                {SpreadsheetReader.TERM, "Kỳ học"}, {SpreadsheetReader.MAJOR, "Ngành"}}) {
            if (!table.has(col[0])) {
                missing.add(col[1]);
            }
        }
        if (!missing.isEmpty()) {
            throw new BadRequestException("The file is missing the column(s): " + String.join(", ", missing));
        }

        Map<String, Integer> codeCount = new HashMap<>();
        Map<String, Integer> emailCount = new HashMap<>();
        for (Line line : table.lines()) {
            String email = lower(line.get(SpreadsheetReader.EMAIL));
            if (email != null) {
                emailCount.merge(email, 1, Integer::sum);
            }
            String code = line.get(SpreadsheetReader.CODE);
            if (code != null) {
                codeCount.merge(code.toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        Map<String, String> newMajors = new LinkedHashMap<>(); // folded name -> display name
        List<RowError> errors = new ArrayList<>();
        List<Row> valid = new ArrayList<>();
        for (Line line : table.lines()) {
            String error = null;
            String code = upper(line.get(SpreadsheetReader.CODE));
            String name = line.get(SpreadsheetReader.NAME);
            String email = lower(line.get(SpreadsheetReader.EMAIL));
            String phone = line.get(SpreadsheetReader.PHONE);
            String termText = line.get(SpreadsheetReader.TERM);
            String majorText = line.get(SpreadsheetReader.MAJOR);
            Integer term = parseTerm(termText);
            if (code == null || name == null || email == null) {
                error = "Thiếu cột bắt buộc (mã số, họ tên hoặc email)";
            } else if (code.length() > 20) {
                error = "Sai định dạng mã số";
            } else if (codeCount.getOrDefault(code, 0) > 1) {
                error = "Trùng mã số trong file";
            } else if (!EMAIL.matcher(email).matches()) {
                error = "Sai định dạng email";
            } else if (emailCount.getOrDefault(email, 0) > 1) {
                error = "Trùng email trong file";
            } else if (phone != null && !PHONE.matcher(phone).matches()) {
                error = "Sai định dạng số điện thoại";
            } else if (termText == null) {
                error = "Thiếu kỳ học";
            } else if (term == null) {
                error = "Sai định dạng kỳ học (số từ 1 đến 12)";
            } else if (term != 8) {
                error = "Chỉ nhập sinh viên kỳ 8 để ghép nhóm cho Capstone kỳ 9";
            } else if (majorText == null) {
                error = "Thiếu ngành";
            }
            if (error != null) {
                errors.add(new RowError(line.rowNumber(), code, error));
                continue;
            }
            ImportedEligibility decision;
            try {
                decision = ImportedEligibility.optional(line);
            } catch (BadRequestException ex) {
                errors.add(new RowError(line.rowNumber(), code, ex.getMessage()));
                continue;
            }
            Major major = majors.findByCodeIgnoreCase(majorText).or(() -> majors.findByNameIgnoreCase(majorText)).orElse(null);
            User byCode = users.findByStudentCodeIgnoreCase(code).orElse(null);
            User byEmail = users.findByEmailIgnoreCase(email).orElse(null);
            if (byEmail != null && byEmail.getRole() != Role.STUDENT) {
                errors.add(new RowError(line.rowNumber(), code, "Email đã thuộc tài khoản giảng viên hoặc quản trị"));
                continue;
            }
            if (byEmail != null && byCode != null && !byEmail.getId().equals(byCode.getId())) {
                errors.add(new RowError(line.rowNumber(), code, "Email đã thuộc sinh viên khác (" + byEmail.getStudentCode() + ")"));
                continue;
            }
            if (byCode == null && byEmail != null && byEmail.getStudentCode() != null
                    && !byEmail.getStudentCode().equalsIgnoreCase(code)) {
                errors.add(new RowError(line.rowNumber(), code, "Email đã thuộc sinh viên khác (" + byEmail.getStudentCode() + ")"));
                continue;
            }
            User user = byCode != null ? byCode : byEmail;
            SemesterStudent existing = user == null ? null
                    : semesterStudents.findBySemesterCodeAndUserId(semesterCode, user.getId()).orElse(null);
            String locked = structuralLock(semester, existing, major);
            if (locked != null) {
                errors.add(new RowError(line.rowNumber(), code, locked));
                continue;
            }
            if (major == null) {
                newMajors.putIfAbsent(SpreadsheetReader.fold(majorText), majorText);
            }
            valid.add(new Row(line, code, name, email, phone, term, SpreadsheetReader.fold(majorText), major, user, existing, decision));
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Row row : valid) {
            if (row.existing() == null) {
                created++;
            } else if (changes(row)) {
                updated++;
            } else {
                unchanged++;
            }
        }
        if (!commit) {
            return new ImportReport(false, null, created, updated, unchanged, errors.size(), errors,
                    List.copyOf(newMajors.values()), List.of());
        }
        if (valid.isEmpty()) {
            throw new BadRequestException("No row can be imported; fix the rows in error and try again");
        }

        Map<String, Major> createdMajors = new HashMap<>();
        List<com.capstone.tracking.roster.EligibilityService.Change> eligibilityChanges = new ArrayList<>();
        newMajors.forEach((folded, display) -> createdMajors.put(folded, majors.saveAndFlush(
                Major.builder().code(majorCode(display)).name(display.trim()).build())));
        for (Row row : valid) {
            Major major = row.major() != null ? row.major() : createdMajors.get(row.majorKey());
            User user = row.user();
            if (user == null) {
                user = User.builder().email(row.email()).fullName(row.name()).role(Role.STUDENT)
                        .status(UserStatus.ACTIVE).build();
            }
            user.setEmail(row.email());
            user.setFullName(row.name());
            user.setStudentCode(row.code());
            if (row.phone() != null) {
                user.setPhone(row.phone());
            }
            user = users.saveAndFlush(user);
            SemesterStudent ss = row.existing();
            if (ss == null) {
                ss = SemesterStudent.builder().semesterCode(semesterCode).user(user).major(major)
                        .currentTerm(row.term()).build();
            } else {
                ss.setMajor(major);
                ss.setCurrentTerm(row.term());
            }
            ss = semesterStudents.saveAndFlush(ss);
            Enrollment e = sync.syncStudent(ss);
            enrollments.flush();
            if (row.decision() != null) {
                eligibilityChanges.add(new com.capstone.tracking.roster.EligibilityService.Change(
                        e.getId(), row.decision().value(), row.decision().reason()));
            }
        }
        eligibilityService.apply(eligibilityChanges, actor);
        ImportBatch batch = batches.save(ImportBatch.builder()
                .kind(ImportBatch.Kind.STUDENTS)
                .semesterCode(semesterCode)
                .fileName(fileName)
                .importedBy(actor.getId())
                .importedAt(Instant.now())
                .createdCount(created)
                .updatedCount(updated)
                .errorCount(errors.size())
                .errorsJson(json(errors))
                .build());
        audit.record("ImportBatch", batch.getId(), AuditAction.IMPORT_STUDENTS, actor, semesterCode,
                Map.of("file", Objects.toString(fileName, ""), "new", created, "updated", updated, "errors", errors.size()));
        return new ImportReport(true, batch.getId(), created, updated, unchanged, errors.size(), errors,
                List.copyOf(newMajors.values()), List.of());
    }

    /**
     * Once the semester has left OPENING the list is fixed except for contact details (4.1): no new student. A
     * student's major never changes (3.3).
     */
    private String structuralLock(Semester semester, SemesterStudent existing, Major major) {
        if (existing == null) {
            return semester.getPhase() == com.capstone.tracking.semester.SemesterPhase.OPENING ? null
                    : "Học kỳ đã qua giai đoạn Mở học kỳ: không thêm sinh viên mới";
        }
        if (major == null || !existing.getMajor().getId().equals(major.getId())) {
            return "Không đổi ngành: ngành của sinh viên không được thay đổi";
        }
        return null;
    }

    private boolean changes(Row row) {
        User u = row.user();
        SemesterStudent ss = row.existing();
        boolean eligibilityChanged = row.decision() != null && enrollments.findBySemesterStudentId(ss.getId())
                .map(e -> e.getEligibility() != row.decision().value()
                        || !Objects.equals(e.getEligibilityReason(), row.decision().reason())).orElse(true);
        return eligibilityChanged || !row.email().equalsIgnoreCase(u.getEmail()) || !row.name().equals(u.getFullName())
                || (row.phone() != null && !row.phone().equals(u.getPhone()))
                || ss.getCurrentTerm() != row.term() || row.major() == null
                || !ss.getMajor().getId().equals(row.major().getId());
    }

    /** Initials of the major's name ("Kỹ thuật phần mềm" -> KTPM), made unique with a number when taken. */
    private String majorCode(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : SpreadsheetReader.fold(name).split(" ")) {
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        String base = initials.isEmpty() ? "MAJOR" : initials.length() > 16 ? initials.substring(0, 16) : initials.toString();
        String code = base;
        for (int n = 2; majors.existsByCodeIgnoreCase(code); n++) {
            code = base + n;
        }
        return code;
    }

    private static Integer parseTerm(String text) {
        if (text == null) {
            return null;
        }
        String digits = SpreadsheetReader.fold(text).replaceFirst("^(ky( hoc)?|term)\\s*", "");
        if (!digits.matches("[0-9]{1,2}")) {
            return null;
        }
        if (digits.isEmpty() || digits.length() > 2) {
            return null;
        }
        int term = Integer.parseInt(digits);
        return term >= 1 && term <= 12 ? term : null;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
