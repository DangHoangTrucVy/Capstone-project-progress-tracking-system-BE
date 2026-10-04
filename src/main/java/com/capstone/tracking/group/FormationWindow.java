package com.capstone.tracking.group;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-semester group formation settings (Admin, "theo đợt"): the Apply/Invite lifetime (YC14) and the cut-off of
 * the permitted period (YC17, YC21). Until the cut-off students create, join and leave groups themselves; afterwards
 * the roster only changes through the supervisor and the Admin, exactly as after Locked.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormationWindow {

    private final SemesterJoinSettingsRepository settings;

    public int ttlHours(String semester) {
        return settings.findById(semester).map(SemesterJoinSettings::getTtlHours)
                .orElse(SemesterJoinSettings.DEFAULT_TTL_HOURS);
    }

    public Instant deadline(String semester) {
        return settings.findById(semester).map(SemesterJoinSettings::getFormationDeadline).orElse(null);
    }

    @Transactional
    public int setTtlHours(String semester, int hours) {
        if (hours < 1) {
            throw new BadRequestException("The request lifetime must be at least 1 hour");
        }
        SemesterJoinSettings row = settings.findById(semester)
                .orElseGet(() -> new SemesterJoinSettings(semester, SemesterJoinSettings.DEFAULT_TTL_HOURS));
        row.setTtlHours(hours);
        settings.save(row);
        return hours;
    }

    /** @param deadline null removes the cut-off */
    @Transactional
    public Instant setDeadline(String semester, Instant deadline) {
        SemesterJoinSettings row = settings.findById(semester)
                .orElseGet(() -> new SemesterJoinSettings(semester, SemesterJoinSettings.DEFAULT_TTL_HOURS));
        row.setFormationDeadline(deadline);
        settings.save(row);
        return deadline;
    }

    /** Students' own roster actions (create, Apply, Invite, Accept, ask to leave) stop at the cut-off. */
    public void requireOpen(String semester) {
        Instant deadline = deadline(semester);
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            throw new ConflictException("Group formation for semester " + semester + " closed at "
                    + VnTime.format(deadline) + "; roster changes now go through the supervisor and an administrator");
        }
    }
}
