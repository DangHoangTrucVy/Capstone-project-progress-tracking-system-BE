package com.capstone.tracking.roster.importing;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.roster.ImportBatch;
import com.capstone.tracking.roster.ImportBatchRepository;
import com.capstone.tracking.roster.importing.ImportReport.RowError;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Line;
import com.capstone.tracking.roster.importing.SpreadsheetReader.Table;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lecturer list (4.2): name and email. Lecturers sign in only with an email on this list, and the Moderator of a
 * semester is chosen from it. An email cannot be both a student's and a lecturer's.
 */
@Service
@RequiredArgsConstructor
public class LecturerImportService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final ImportBatchRepository batches;
    private final Access access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    @Transactional
    public ImportReport process(String fileName, byte[] data, boolean commit, User actor) {
        access.requireAdmin(actor);
        Table table = SpreadsheetReader.read(data);
        if (!table.has(SpreadsheetReader.NAME) || !table.has(SpreadsheetReader.EMAIL)) {
            throw new BadRequestException("The file needs the columns: Họ tên, Email");
        }
        Map<String, Integer> emailCount = new HashMap<>();
        table.lines().forEach(l -> {
            String email = l.get(SpreadsheetReader.EMAIL);
            if (email != null) {
                emailCount.merge(email.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        });
        List<RowError> errors = new ArrayList<>();
        List<String[]> valid = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Line line : table.lines()) {
            String name = line.get(SpreadsheetReader.NAME);
            String email = line.get(SpreadsheetReader.EMAIL) == null ? null
                    : line.get(SpreadsheetReader.EMAIL).toLowerCase(Locale.ROOT);
            if (name == null || email == null) {
                errors.add(new RowError(line.rowNumber(), email, "Thiếu họ tên hoặc email"));
                continue;
            }
            if (!EMAIL.matcher(email).matches()) {
                errors.add(new RowError(line.rowNumber(), email, "Sai định dạng email"));
                continue;
            }
            if (emailCount.getOrDefault(email, 0) > 1) {
                errors.add(new RowError(line.rowNumber(), email, "Trùng email trong file"));
                continue;
            }
            User existing = users.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null && existing.getRole() == Role.STUDENT) {
                errors.add(new RowError(line.rowNumber(), email, "Email đã thuộc tài khoản sinh viên"));
                continue;
            }
            if (existing != null && existing.getRole() == Role.ADMIN) {
                errors.add(new RowError(line.rowNumber(), email, "Email đã thuộc tài khoản quản trị"));
                continue;
            }
            if (existing == null) {
                created++;
            } else if (!existing.getFullName().equals(name)) {
                updated++;
            } else {
                unchanged++;
            }
            valid.add(new String[]{email, name});
        }
        if (!commit) {
            return new ImportReport(false, null, created, updated, unchanged, errors.size(), errors, List.of(), List.of());
        }
        if (valid.isEmpty()) {
            throw new BadRequestException("No row can be imported; fix the rows in error and try again");
        }
        for (String[] row : valid) {
            User user = users.findByEmailIgnoreCase(row[0]).orElseGet(() -> User.builder()
                    .email(row[0]).role(Role.LECTURER).status(UserStatus.ACTIVE).build());
            user.setFullName(row[1]);
            users.save(user);
        }
        ImportBatch batch = batches.save(ImportBatch.builder()
                .kind(ImportBatch.Kind.LECTURERS)
                .fileName(fileName)
                .importedBy(actor.getId())
                .importedAt(Instant.now())
                .createdCount(created)
                .updatedCount(updated)
                .errorCount(errors.size())
                .errorsJson(json(errors))
                .build());
        audit.record("ImportBatch", batch.getId(), AuditAction.IMPORT_LECTURERS, actor, null,
                Map.of("file", Objects.toString(fileName, ""), "new", created, "updated", updated, "errors", errors.size()));
        return new ImportReport(true, batch.getId(), created, updated, unchanged, errors.size(), errors, List.of(), List.of());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
