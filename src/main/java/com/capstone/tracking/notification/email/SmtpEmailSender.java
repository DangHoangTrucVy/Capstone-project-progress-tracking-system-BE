package com.capstone.tracking.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Uses the JavaMailSender Spring Boot configures from {@code spring.mail.*} (e.g. Gmail SMTP with an app password).
 * The sender defaults to the SMTP account, because Gmail rewrites or rejects any other From address.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${app.mail.from:}") String from,
                           @Value("${spring.mail.username:}") String username) {
        this.mailSender = mailSender;
        this.from = StringUtils.hasText(from) ? from.trim() : username.trim();
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("app.mail.enabled=true but MAIL_USERNAME is empty");
        }
        log.info("SMTP email enabled: sending as {}", this.from);
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to().toArray(String[]::new));
        mail.setCc(message.cc().toArray(String[]::new));
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
    }
}
