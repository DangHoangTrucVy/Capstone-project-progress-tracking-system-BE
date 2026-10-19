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
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopePhase;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
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
 * importing again never duplicates anyone; students missing from a later file are left as they are. Once a scope has
 * opened, a row may only update contact details (name, email, phone): new students and changes of major or term in
 * an opened scope are rejected. The same call previews (nothing written) or, confirmed, writes.
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
    private final SubjectRepository subjects;
    private final FormationScopeRepository scopes;
    private final ImportBatchRepository batches;
    private final RosterSync sync;
    private final Access access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    private record Row(Line line, String code, String name, String email, String phone, int term, String majorKey,
                       Major major, User user, SemesterStudent existing) {}

    @Transactional
    public ImportReport process(String semesterCode, String fileName, byte[] data, boolean commit, User actor) {
        access.requireAdmin(actor);
        Semester semester = access.requireModerator(semesterCode, actor);
        if (semester.getArchivedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SEMESTER_ARCHIVED", "The semester is archived");
        }
        Table table = SpreadsheetReader.read(data);
        List<String> missing = new ArrayList<>();
        for (String[] col : new String[][]{{SpreadsheetReader.CODE, "Mã số sinh viên"}, {SpreadsheetReader.NAME, "Họ tên"},
                {SpreadsheetReader.EMAIL, "Email"}, {SpreadsheetReader.PHONE, "Số điện thoại"},
                {SpreadsheetReader.TERM, "Kỳ học"}, {SpreadsheetReader.MAJOR, "Ngành"}}) {
            if (!table.has(col[0])) {
                missing.add(col[1]);
            }
        }
        if (!missing.isEmpty()) {
            throw new BadRequestException("The file is missing the column(s): " + String.join(", ", missing));
        }

        Map<String, Integer> codeCount = new HashMap<>();
        for (Line line : table.lines()) {
            String code = line.get(SpreadsheetReader.CODE);
            if (code != null) {
                codeCount.merge(code.toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        List<Subject> semesterSubjects = subjects.findBySemesterCodeOrderByCodeAsc(semesterCode);
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
            } else if (phone != null && !PHONE.matcher(phone).matches()) {
                error = "Sai định dạng số điện thoại";
            } else if (termText == null) {
                error = "Thiếu kỳ học";
            } else if (term == null) {
                error = "Sai định dạng kỳ học (số từ 1 đến 12)";
            } else if (majorText == null) {
                error = "Thiếu ngành";
            }
            if (error != null) {
                errors.add(new RowError(line.rowNumber(), code, error));
                continue;
            }
            Major major = majors.findByCodeIgnoreCase(majorText).or(() -> majors.findByNameIgnoreCase(majorText)).orElse(null);
            if (major == null) {
                newMajors.putIfAbsent(SpreadsheetReader.fold(majorText), majorText);
            }
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
            String locked = structuralLock(semesterSubjects, existing, term, major);
            if (locked != null) {
                errors.add(new RowError(line.rowNumber(), code, locked));
                continue;
            }
            valid.add(new Row(line, code, name, email, phone, term, SpreadsheetReader.fold(majorText), major, user, existing));
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
            sync.syncStudent(ss);
        }
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
     * Once a scope has opened, the list is fixed except for contact details (4.1): no new student in an opened scope,
     * no change of major or term for a student whose scope opened.
     */
    private String structuralLock(List<Subject> semesterSubjects, SemesterStudent existing, int term, Major major) {
        if (existing == null) {
            if (major != null && opened(semesterSubjects, term, major)) {
                return "Phạm vi ghép nhóm đã mở: không thêm sinh viên mới";
            }
            return null;
        }
        boolean changed = existing.getCurrentTerm() != term || major == null
                || !existing.getMajor().getId().equals(major.getId());
        if (!changed) {
            return null;
        }
        boolean currentOpened = enrollments.findBySemesterStudentId(existing.getId()).stream()
                .map(Enrollment::getScope).anyMatch(s -> s.getPhase() != ScopePhase.PREPARING);
        if (currentOpened || (major != null && opened(semesterSubjects, term, major))) {
            return "Phạm vi ghép nhóm đã mở: không đổi ngành hoặc kỳ học";
        }
        return null;
    }

    private boolean opened(List<Subject> semesterSubjects, int term, Major major) {
        return semesterSubjects.stream()
                .filter(s -> s.getTargetTerm() == term)
                .map(s -> scopes.findBySubjectIdAndMajorId(s.getId(), major.getId()).orElse(null))
                .anyMatch(scope -> scope != null && scope.getPhase() != ScopePhase.PREPARING);
    }

    private static boolean changes(Row row) {
        User u = row.user();
        SemesterStudent ss = row.existing();
        return !row.email().equalsIgnoreCase(u.getEmail()) || !row.name().equals(u.getFullName())
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
        String digits = text.replaceAll("[^0-9]", "");
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
