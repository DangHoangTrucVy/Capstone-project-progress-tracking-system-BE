package com.capstone.tracking.notification.email;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling for {@link EmailOutboxDispatcher} (the SQS poller enables it too; enabling twice is harmless). */
@Configuration
@EnableScheduling
public class EmailConfig {
}
