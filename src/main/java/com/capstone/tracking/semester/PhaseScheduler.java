package com.capstone.tracking.semester;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Locks the rooms of every semester that reached its deadline (7.1) and writes down expired requests. Each semester
 * runs in its own transaction, so one failure does not stop the others. Every phase transition stays the Moderator's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhaseScheduler {

    private final SemesterRepository semesters;
    private final PhaseService phases;

    @Scheduled(initialDelayString = "${app.formation.tick-ms:30000}", fixedDelayString = "${app.formation.tick-ms:30000}")
    public void tick() {
        for (Semester semester : semesters.findDueForRoomLock(SemesterPhase.FORMING, Instant.now())) {
            try {
                phases.tick(semester.getCode());
            } catch (RuntimeException e) {
                log.error("Locking the rooms of semester {} failed", semester.getCode(), e);
            }
        }
        int expired = phases.expireRequests();
        if (expired > 0) {
            log.info("Expired {} unanswered requests and invites", expired);
        }
    }
}
