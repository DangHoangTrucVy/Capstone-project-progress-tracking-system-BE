package com.capstone.tracking.subject;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The deadline and phase checks every roster action relies on ("trước deadline" / "sau deadline", 3.4). */
@Component
@RequiredArgsConstructor
public class ScopeRules {

    private final SemesterRepository semesters;

    /** The scope's own deadline, or the semester's common deadline (3.5); null when neither is set. */
    public Instant deadline(FormationScope scope) {
        if (scope.getDeadlineOverride() != null) {
            return scope.getDeadlineOverride();
        }
        return semesters.findById(scope.semesterCode()).map(Semester::getDefaultDeadline).orElse(null);
    }

    public boolean isOpen(FormationScope scope, Instant now) {
        return scope.isOpenAt(now, deadline(scope));
    }

    /** Students' own roster actions: only while the scope is open and before its deadline. */
    public void requireOpen(FormationScope scope) {
        Instant now = Instant.now();
        if (isOpen(scope, now)) {
            return;
        }
        String message = switch (scope.getPhase()) {
            case PREPARING -> "Group formation has not opened yet for this major";
            case OPEN -> "The deadline (" + VnTime.format(deadline(scope)) + ") has passed; the Moderator now arranges the groups";
            case LOCKED -> "The deadline has passed; the Moderator now arranges the groups";
            case FINALIZED -> "The list has been finalized; it can only be viewed and exported";
        };
        throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_OPEN", message);
    }

    /** Moderator coordination (7.1): only after the deadline and before finalizing. */
    public void requireLocked(FormationScope scope) {
        if (scope.getPhase() != ScopePhase.LOCKED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_NOT_LOCKED",
                    "The Moderator arranges groups only after the deadline and before the list is finalized (phase is "
                            + scope.getPhase() + ")");
        }
    }

    public void requireNotFinalized(FormationScope scope) {
        if (scope.getPhase() == ScopePhase.FINALIZED) {
            throw new ApiException(HttpStatus.CONFLICT, "SCOPE_FINALIZED",
                    "The list has been finalized; reopen it first");
        }
    }
}
