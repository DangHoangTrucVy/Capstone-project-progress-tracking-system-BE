package com.capstone.tracking.notification.email;

/** Outbound email: SMTP when {@code app.mail.enabled=true}, otherwise just logged (dev, tests, Railway without SMTP). */
public interface EmailSender {

    void send(EmailMessage message);
}
