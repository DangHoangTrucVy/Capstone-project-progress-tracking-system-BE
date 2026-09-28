package com.capstone.tracking.notification.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends queued {@link EmailOutbox} rows in the background. A failed send is retried with exponential backoff
 * (1, 2, 4, 8 minutes) and marked FAILED after {@link #MAX_ATTEMPTS}. Each batch runs in its own transaction holding
 * row locks (SKIP LOCKED), so several instances can dispatch concurrently without double-sending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailOutboxDispatcher {

    static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 20;
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);

    private final EmailOutboxRepository outboxRepository;
    private final EmailSender emailSender;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(initialDelayString = "${app.mail.dispatch-delay-ms:5000}", fixedDelayString = "${app.mail.dispatch-delay-ms:5000}")
    public void dispatchScheduled() {
        dispatchPending(Instant.now());
    }

    /** Sends every email due at {@code now}, batch by batch. Returns how many were sent. */
    public int dispatchPending(Instant now) {
        int sent = 0;
        while (true) {
            Integer batch = transactionTemplate.execute(status -> sendBatch(now));
            if (batch == null || batch < 0) {
                return sent;
            }
            sent += batch;
        }
    }

    /** Returns the number sent, or -1 when nothing was due. */
    private int sendBatch(Instant now) {
        List<EmailOutbox> due = outboxRepository.lockDue(now, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) {
            return -1;
        }
        int sent = 0;
        for (EmailOutbox email : due) {
            email.setAttempts(email.getAttempts() + 1);
            try {
                emailSender.send(email.toMessage());
                email.setStatus(EmailOutbox.Status.SENT);
                email.setSentAt(Instant.now());
                email.setLastError(null);
                sent++;
            } catch (RuntimeException e) {
                email.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                if (email.getAttempts() >= MAX_ATTEMPTS) {
                    email.setStatus(EmailOutbox.Status.FAILED);
                    log.error("Giving up on email {} after {} attempts", email.getId(), email.getAttempts(), e);
                } else {
                    email.setNextAttemptAt(now.plus(FIRST_RETRY.multipliedBy(1L << (email.getAttempts() - 1))));
                    log.warn("Email {} failed (attempt {}), retrying at {}", email.getId(), email.getAttempts(),
                            email.getNextAttemptAt(), e);
                }
            }
        }
        return sent;
    }
}
