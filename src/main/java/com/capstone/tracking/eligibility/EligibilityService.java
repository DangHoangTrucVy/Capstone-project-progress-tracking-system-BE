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

    public record Entry(String email, String fullName, boolean eligible, String reason) {}

    public record ImportResult(int created, int updated, int flagged, int cleared, List<String> invalid) {}

    /** Throws 403 NOT_ELIGIBLE when the student is flagged; the message carries the reason. */
    public void requireEligible(User student) {
        if (!student.isEligible()) {
            String reason = student.getIneligibleReason();
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE",
                    "You are not eligible for the capstone" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        }
    }

    /** Upserts the imported students. Existing non-student accounts are never touched. */
    @Transactional
    public ImportResult importList(List<Entry> entries) {
        int created = 0;
        int updated = 0;
        int flagged = 0;
        int cleared = 0;
        List<String> invalid = new ArrayList<>();
        for (Entry entry : entries) {
            String email = entry.email() == null ? "" : entry.email().trim().toLowerCase();
            if (email.isEmpty() || !email.contains("@")) {
                invalid.add(String.valueOf(entry.email()));
                continue;
            }
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (user == null) {
                String name = entry.fullName() == null || entry.fullName().isBlank() ? email : entry.fullName().trim();
                user = userRepository.save(User.builder().email(email).fullName(name).role(Role.STUDENT)
                        .status(UserStatus.ACTIVE).build());
                created++;
            } else if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
                invalid.add(email + " (not a student account)");
                continue;
            } else {
                updated++;
            }
            if (apply(user, entry.eligible(), entry.reason())) {
                if (entry.eligible()) {
                    cleared++;
                } else {
                    flagged++;
                }
            }
        }
        return new ImportResult(created, updated, flagged, cleared, invalid);
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
        List<List<String>> rows = readCsv(new String(data, StandardCharsets.UTF_8).replace("\uFEFF", ""));
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
                default -> { }
            }
        }
        if (!col.containsKey("email")) {
            throw new BadRequestException("The file needs an 'email' column");
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
                    cell(row, col.get("reason"))));
        }
        ImportResult result = importList(entries);
        invalid.addAll(0, result.invalid());
        return new ImportResult(result.created(), result.updated(), result.flagged(), result.cleared(), invalid);
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
