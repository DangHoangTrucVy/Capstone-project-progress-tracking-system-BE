package com.capstone.tracking.semester;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The phase checks every roster action relies on ("trước deadline" / "sau deadline", 3.5, 3.6). */
@Component
public class SemesterGuard {

    /** Students' own room actions (join, leave, lock, kick, finalize request): formation running, before the deadline. */
    public void requireBeforeDeadline(Semester semester) {
        if (semester.isBeforeDeadline(Instant.now())) {
            return;
        }
        String message = switch (semester.getPhase()) {
            case OPENING -> "Group formation has not started yet; students can only view and edit their profile";
            case FORMING -> semester.getDeadline() == null ? "The semester has no deadline"
                    : "The deadline (" + VnTime.format(semester.getDeadline())
                            + ") has passed; rooms are locked and the Moderator arranges the rest";
            case SUPPLEMENT -> "Round 1 has been finalized; only Leaders' invites are open now";
            case CLOSED -> "Group formation has ended; the semester can only be viewed and exported";
        };
        throw new ApiException(HttpStatus.CONFLICT, "NOT_BEFORE_DEADLINE", message);
    }

    /** Leaders' invites to the students left after round 1 (7.4). */
    public void requireSupplement(Semester semester) {
        if (semester.getPhase() != SemesterPhase.SUPPLEMENT) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_SUPPLEMENT",
                    "This is only possible in the supplement phase (phase is " + semester.getPhase() + ")");
        }
    }

    /** The Moderator coordinates in every phase until formation ends (7.2). */
    public void requireNotClosed(Semester semester) {
        if (semester.isClosed()) {
            throw new ApiException(HttpStatus.CONFLICT, "SEMESTER_CLOSED",
                    "Group formation has ended; the semester can only be viewed and exported");
        }
    }

    public void requirePhase(Semester semester, SemesterPhase phase, String what) {
        if (semester.getPhase() != phase) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_PHASE",
                    what + " is only possible in phase " + phase + " (phase is " + semester.getPhase() + ")");
        }
    }

    /** Round 1 is not finalized yet: OPENING or FORMING. */
    public void requireBeforeRound1(Semester semester, String what) {
        if (semester.getPhase() != SemesterPhase.OPENING && semester.getPhase() != SemesterPhase.FORMING) {
            throw new ApiException(HttpStatus.CONFLICT, "AFTER_ROUND1",
                    what + " is only possible before round 1 is finalized");
        }
    }
}
