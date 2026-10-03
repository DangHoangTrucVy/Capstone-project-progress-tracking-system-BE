package com.capstone.tracking.eligibility;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.group.GroupJoinRequestRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserService;
import com.capstone.tracking.user.UserStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
        return changed;
    }
}
