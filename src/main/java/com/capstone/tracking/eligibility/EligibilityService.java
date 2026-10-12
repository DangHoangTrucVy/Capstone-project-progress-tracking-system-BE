package com.capstone.tracking.eligibility;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.group.GroupJoinRequestRepository;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserService;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * YC03/YC04: the training department (Admin) imports the student list and flags students who may not do the
 * capstone; it can lift the flag. Eligibility is independent of signing in: an ineligible student still logs in and
 * sees why, but {@link #requireEligible} stops them from forming or joining a group.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EligibilityService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final GroupJoinRequestRepository joinRequestRepository;
    private final ApplicationEventPublisher events;

    /**
     * One line of the list. {@code email} identifies the student; it may be left out for a student who already has an
     * account with that {@code studentCode} (MSSV). {@code eligible} left out means eligible, as a blank cell in the CSV.
     */
    public record Entry(String email,
                        @JsonAlias({"name", "hoTen", "ho_ten"}) String fullName,
                        @JsonAlias({"duDieuKien", "du_dieu_kien"}) Boolean eligible,
                        @JsonAlias({"lyDo", "ly_do", "ineligibleReason"}) String reason,
                        @JsonAlias({"mssv", "studentId", "student_code"}) String studentCode) {
        public Entry(String email, String fullName, boolean eligible, String reason) {
            this(email, fullName, eligible, reason, null);
        }

        boolean isEligible() {
            return eligible == null || eligible;
        }
    }

    public enum ImportAction { CREATED, UPDATED }

    /** A student the import created or updated, as it stands after the import. */
    public record ImportedStudent(UUID userId, String email, String fullName, String studentCode, boolean eligible,
                                  String ineligibleReason, ImportAction action) {
        static ImportedStudent of(User u, ImportAction action) {
            return new ImportedStudent(u.getId(), u.getEmail(), u.getFullName(), u.getStudentCode(), u.isEligible(),
                    u.getIneligibleReason(), action);
        }
    }

    /** The counts, plus the detail of every created/updated student ({@code students}). */
    public record ImportResult(int created, int updated, int flagged, int cleared, List<String> invalid,
                               List<ImportedStudent> students) {}

    /** Throws 403 NOT_ELIGIBLE when the student is flagged; the message carries the reason. */
    public void requireEligible(User student) {
        if (!student.isEligible()) {
            String reason = student.getIneligibleReason();
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE",
                    "You are not eligible for the capstone" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        }
    }

    /**
     * Upserts the imported students. Existing non-student accounts are never touched. Lines that cannot be used are
     * listed in {@code invalid}; when no line at all can be used the import fails with 400 instead of answering
     * "0 created" as if it had worked.
     */
    @Transactional
    public ImportResult importList(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new BadRequestException("The student list is empty");
        }
        return requireSomethingImported(upsert(entries));
    }

    private static ImportResult requireSomethingImported(ImportResult result) {
        if (result.students().isEmpty() && !result.invalid().isEmpty()) {
            List<String> shown = result.invalid().subList(0, Math.min(10, result.invalid().size()));
            throw new BadRequestException("No student was imported. Invalid lines: " + String.join("; ", shown)
                    + (result.invalid().size() > shown.size() ? " (+" + (result.invalid().size() - shown.size()) + " more)" : ""));
        }
        return result;
    }

    private ImportResult upsert(List<Entry> entries) {
        int created = 0;
        int updated = 0;
        int flagged = 0;
        int cleared = 0;
        List<String> invalid = new ArrayList<>();
        List<ImportedStudent> students = new ArrayList<>();
        Instant now = Instant.now();
        int line = 0;
        for (Entry entry : entries) {
            line++;
            String email = entry.email() == null ? "" : entry.email().trim().toLowerCase();
            String givenCode = entry.studentCode() == null ? "" : entry.studentCode().trim();
            User user;
            if (email.isEmpty()) {
                // No e-mail: only an existing account can be matched, by its MSSV.
                user = givenCode.isEmpty() ? null : userRepository.findByStudentCodeIgnoreCase(givenCode).orElse(null);
                if (user == null) {
                    invalid.add("line " + line + ": email is required"
                            + (givenCode.isEmpty() ? "" : " (no account has student code " + givenCode + ")"));
                    continue;
                }
                email = user.getEmail();
            } else if (!email.contains("@")) {
                invalid.add("line " + line + ": invalid email '" + entry.email().trim() + "'");
                continue;
            } else {
                user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            }
            ImportAction action;
            if (user == null) {
                String name = entry.fullName() == null || entry.fullName().isBlank() ? email : entry.fullName().trim();
                user = userRepository.save(User.builder().email(email).fullName(name).role(Role.STUDENT)
                        .status(UserStatus.ACTIVE).build());
                created++;
                action = ImportAction.CREATED;
            } else if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
                invalid.add(email + " (not a student account)");
                continue;
            } else {
                updated++;
                action = ImportAction.UPDATED;
            }
            String code = entry.studentCode() == null ? null : entry.studentCode().trim();
            if (code != null && !code.isEmpty() && !code.equalsIgnoreCase(user.getStudentCode())) {
                UUID self = user.getId();
                if (code.length() > 20 || userRepository.findByStudentCodeIgnoreCase(code)
                        .filter(other -> !other.getId().equals(self)).isPresent()) {
                    invalid.add(email + " (student code " + code + " is invalid or used by another account)");
                } else {
                    user.setStudentCode(code);
                }
            }
            user.setImportedAt(now);
            if (apply(user, entry.isEligible(), entry.reason())) {
                if (entry.isEligible()) {
                    cleared++;
                } else {
                    flagged++;
                }
            }
            students.add(ImportedStudent.of(user, action));
        }
        return new ImportResult(created, updated, flagged, cleared, invalid, students);
    }

    /** Flag (eligible=false, with a reason) or un-flag a single student. */
    @Transactional
    public User setEligibility(UUID userId, boolean eligible, String reason) {
        User user = userService.getById(userId);
        if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
            throw new BadRequestException("Only student accounts have an eligibility flag");
        }
        apply(user, eligible, reason);
        return user;
    }

    /** Students the list import created or updated (latest state), for the Admin's "imported students" screen. */
    public Page<User> listImported(Boolean eligible, String keyword, Pageable pageable) {
        String q = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.searchImported(eligible == null, eligible == null || eligible, q, pageable);
    }

    public Page<User> listIneligible(Pageable pageable) {
        return userRepository.findByEligibleFalse(pageable);
    }

    /** @return whether the flag changed. Open Apply/Invite of a newly flagged student are cancelled (YC04). */
    private boolean apply(User user, boolean eligible, String reason) {
        boolean changed = user.isEligible() != eligible;
        user.setEligible(eligible);
        user.setIneligibleReason(eligible ? null : (reason == null || reason.isBlank() ? null : reason.trim()));
        if (changed && !eligible) {
            joinRequestRepository.cancelAllPending(user.getId(), Instant.now());
        }
        if (!eligible) {
            // YC04: an ineligible student cannot stay in a group; the group module removes them in this transaction.
            events.publishEvent(new StudentMarkedIneligible(user.getId(), user.getIneligibleReason()));
        }
        if (changed) {
            // YC03: the student is told about the flag and the reason, or that it was lifted.
            String label = eligible
                    ? "Bạn đã được xác nhận đủ điều kiện làm Capstone"
                    : "Bạn được xác định chưa đủ điều kiện làm Capstone"
                            + (user.getIneligibleReason() == null ? "" : ": " + user.getIneligibleReason());
            events.publishEvent(DomainEvent.of(DomainEventType.ELIGIBILITY_CHANGED, null, user.getId(), null, label)
                    .withDetails(user.getIneligibleReason(), null).withTarget(user.getId()));
        }
        return changed;
    }

    /**
     * YC03: the training department's list as a UTF-8 CSV file (comma or semicolon separated). Header names,
     * Vietnamese or English, any case: {@code email}, {@code ho_ten|full_name|name},
     * {@code du_dieu_kien|eligible} (true/false, 1/0, có/không, x; blank = eligible), {@code ly_do|reason}.
     */
    @Transactional
    public ImportResult importCsv(byte[] data) {
        List<List<String>> rows = isExcel(data) ? readExcel(data)
                : readCsv(new String(data, StandardCharsets.UTF_8).replace("\uFEFF", ""));
        if (rows.isEmpty()) {
            throw new BadRequestException("The file is empty");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = fold(header.get(i)).replace('_', ' ').trim();
            switch (key) {
                case "email", "e-mail", "mail" -> col.putIfAbsent("email", i);
                case "ho ten", "ho va ten", "full name", "fullname", "name" -> col.putIfAbsent("name", i);
                case "du dieu kien", "dieu kien", "eligible" -> col.putIfAbsent("eligible", i);
                case "ly do", "reason" -> col.putIfAbsent("reason", i);
                case "mssv", "ma sv", "ma so sinh vien", "ma sinh vien", "student code", "studentcode", "student id" ->
                        col.putIfAbsent("code", i);
                default -> { }
            }
        }
        if (!col.containsKey("email") && !col.containsKey("code")) {
            throw new BadRequestException("The file needs an 'email' column (or 'mssv' for students who already have an"
                    + " account); supported formats: .csv, .xlsx, .xls");
        }
        List<Entry> entries = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            String flag = cell(row, col.get("eligible"));
            Boolean eligible = parseEligible(flag);
            if (eligible == null) {
                invalid.add("row " + (r + 1) + ": eligible value '" + flag + "'");
                continue;
            }
            entries.add(new Entry(cell(row, col.get("email")), cell(row, col.get("name")), eligible,
                    cell(row, col.get("reason")), cell(row, col.get("code"))));
        }
        if (entries.isEmpty() && invalid.isEmpty()) {
            throw new BadRequestException("The file has no student rows");
        }
        ImportResult result = upsert(entries);
        invalid.addAll(0, result.invalid());
        return requireSomethingImported(new ImportResult(result.created(), result.updated(), result.flagged(),
                result.cleared(), invalid, result.students()));
    }

    private static Boolean parseEligible(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return switch (fold(raw).trim()) {
            case "true", "1", "yes", "y", "co", "x", "du", "dat" -> true;
            case "false", "0", "no", "n", "khong", "chua du", "khong du" -> false;
            default -> null;
        };
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index >= row.size()) {
            return null;
        }
        String v = row.get(index).trim();
        return v.isEmpty() ? null : v;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    /** .xlsx is a zip archive ("PK\3\4"); .xls (Excel 97-2003) is an OLE2 compound file (D0 CF 11 E0). */
    private static boolean isExcel(byte[] data) {
        boolean xlsx = data.length > 3 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        boolean xls = data.length > 3 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF
                && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0;
        return xlsx || xls;
    }

    /** The first sheet of an Excel file (.xlsx or .xls), every cell read as the text Excel shows. */
    static List<List<String>> readExcel(byte[] data) {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException("Cannot read the Excel file: " + e.getMessage());
        }
        return rows;
    }

    /** RFC 4180: quoted fields may hold separators, doubled quotes and line breaks. */
    static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',' || ch == ';') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
