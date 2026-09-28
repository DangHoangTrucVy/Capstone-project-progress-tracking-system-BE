package com.capstone.tracking.notification;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.notification.email.EmailOutbox;
import com.capstone.tracking.notification.email.EmailOutboxRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Emails leave the request path: queued in the outbox with the notification, sent and retried by the dispatcher. */
class EmailOutboxIntegrationTest extends WorkflowTestSupport {

    @Autowired private EmailOutboxRepository outboxRepository;

    private User supervisor;
    private User leader;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        supervisor = user("ob-gv", Role.INSTRUCTOR);
        leader = user("ob-leader", Role.GROUP_LEADER);
        group = group("OB", supervisor, true);
        join(group, leader, true);
        reset(emailSender);
    }

    @Test
    void requestOnlyQueuesTheEmailAndTheDispatcherSendsItOnce() throws Exception {
        raiseFlag();

        assertThat(sendsForGroup()).isZero();
        EmailOutbox queued = outboxFor(group);
        assertThat(queued.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(queued.getRecipientsTo()).isEqualTo(leader.getEmail());

        dispatchEmails();
        dispatchEmails();
        assertThat(sendsForGroup()).isEqualTo(1);
        assertThat(outboxRepository.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo(EmailOutbox.Status.SENT);
    }

    @Test
    void failedSendIsRetriedWithBackoffThenGivenUp() throws Exception {
        doThrow(new MailSendException("SMTP down")).when(emailSender).send(any());
        raiseFlag();
        EmailOutbox queued = outboxFor(group);
        Instant now = Instant.now();

        emailOutboxDispatcher.dispatchPending(now);
        EmailOutbox afterFirst = outboxRepository.findById(queued.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).contains("SMTP down");
        assertThat(afterFirst.getNextAttemptAt()).isAfter(now.plus(Duration.ofSeconds(59)));

        // Not due yet: nothing is tried.
        emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofSeconds(30)));
        assertThat(sendsForGroup()).isEqualTo(1);

        // Attempts 2-5 at ever later times, then FAILED.
        for (int i = 0; i < 4; i++) {
            emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofDays(1 + i)));
        }
        EmailOutbox gaveUp = outboxRepository.findById(queued.getId()).orElseThrow();
        assertThat(gaveUp.getAttempts()).isEqualTo(5);
        assertThat(gaveUp.getStatus()).isEqualTo(EmailOutbox.Status.FAILED);
        emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofDays(30)));
        assertThat(sendsForGroup()).isEqualTo(5);
    }

    private void raiseFlag() throws Exception {
        postJson("/api/v1/groups/" + group.getId() + "/warning-flags", supervisor,
                Map.of("type", "GROUP_BEHIND_SCHEDULE", "severity", "LOW", "reason", "Chậm sprint 2"))
                .andExpect(status().isCreated());
    }

    /** Send attempts for this test's group only: other tests' pending emails share the database. */
    private long sendsForGroup() {
        return mockingDetails(emailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (EmailMessage) i.getArgument(0))
                .filter(m -> m.subject().contains(group.getGroupCode()))
                .count();
    }

    private EmailOutbox outboxFor(StudentGroup g) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getSubject().contains(g.getGroupCode()))
                .findFirst().orElseThrow();
    }
}
