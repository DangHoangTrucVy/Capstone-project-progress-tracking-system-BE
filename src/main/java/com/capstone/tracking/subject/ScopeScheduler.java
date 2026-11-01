package com.capstone.tracking.subject;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the automatic transitions (3.4): PREPARING -> OPEN on the common opening date, OPEN -> LOCKED at the deadline,
 * and writes down expired Apply/Invite requests. Each scope runs in its own transaction, so one failure does not stop
 * the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeScheduler {

    private final FormationScopeRepository scopes;
    private final ScopeService scopeService;

    @Scheduled(initialDelayString = "${app.formation.tick-ms:30000}", fixedDelayString = "${app.formation.tick-ms:30000}")
    public void tick() {
        List<FormationScope> due = scopes.findByPhaseIn(List.of(ScopePhase.PREPARING, ScopePhase.OPEN));
        for (FormationScope scope : due) {
            try {
                scopeService.tick(scope.getId());
            } catch (RuntimeException e) {
                log.error("Automatic transition of scope {} failed", scope.getId(), e);
            }
        }
        int expired = scopeService.expireRequests();
        if (expired > 0) {
            log.info("Expired {} unanswered Apply/Invite requests", expired);
        }
    }
}
