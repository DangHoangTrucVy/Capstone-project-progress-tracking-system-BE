package com.capstone.tracking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.notification.email.EmailOutbox;
import com.capstone.tracking.notification.email.EmailOutboxRepository;
import com.capstone.tracking.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;

/** Emails leave the request path: queued in the outbox with the notification, sent and retried by the dispatcher. */
class EmailOutboxIntegrationTest extends TestSupport {

    @Autowired private EmailOutboxRepository outboxRepository;

    private World w;
    private User leader;
    private User applicant;
    private UUID group;

    @BeforeEach
    void setUp() throws Exception {
        w = world(2, 0);
        leader = w.students().get(0);
        applicant = w.students().get(1);
        group = createGroup(w, leader, "Mail team");
        dispatchEmails(); // the "formation opened" emails of world()
        reset(emailSender);
    }

    @Test
    void requestOnlyQueuesTheEmailAndTheDispatcherSendsItOnce() throws Exception {
        apply();

        assertThat(sendsTo(leader)).isZero();
        EmailOutbox queued = outboxFor(leader);
        assertThat(queued.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(queued.getRecipientsTo()).isEqualTo(leader.getEmail());

        dispatchEmails();
        dispatchEmails();
        assertThat(sendsTo(leader)).isEqualTo(1);
        assertThat(outboxRepository.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo(EmailOutbox.Status.SENT);
    }

    @Test
    void failedSendIsRetriedWithBackoffThenGivenUp() throws Exception {
        doThrow(new MailSendException("SMTP down")).when(emailSender).send(any());
        apply();
        EmailOutbox queued = outboxFor(leader);
        Instant now = Instant.now();

        emailOutboxDispatcher.dispatchPending(now);
        EmailOutbox afterFirst = outboxRepository.findById(queued.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).contains("SMTP down");
        assertThat(afterFirst.getNextAttemptAt()).isAfter(now.plus(Duration.ofSeconds(59)));

        emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofSeconds(30)));
        assertThat(sendsTo(leader)).isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofDays(1 + i)));
        }
        EmailOutbox gaveUp = outboxRepository.findById(queued.getId()).orElseThrow();
        assertThat(gaveUp.getAttempts()).isEqualTo(5);
        assertThat(gaveUp.getStatus()).isEqualTo(EmailOutbox.Status.FAILED);
        emailOutboxDispatcher.dispatchPending(now.plus(Duration.ofDays(30)));
        assertThat(sendsTo(leader)).isEqualTo(5);
    }

    private void apply() throws Exception {
        postJson("/api/v1/groups/" + group + "/applications", applicant, java.util.Map.of())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    /** Send attempts of this test's application email only: other emails share the database. */
    private long sendsTo(User user) {
        return mockingDetails(emailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .map(i -> (EmailMessage) i.getArgument(0))
                .filter(m -> m.to().contains(user.getEmail()) && m.subject().contains("xin vào nhóm"))
                .count();
    }

    private EmailOutbox outboxFor(User user) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getRecipientsTo().equals(user.getEmail()) && e.getSubject().contains("xin vào nhóm"))
                .findFirst().orElseThrow();
    }
}
