package com.capstone.tracking.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.messaging.*}: {@code type} is inprocess (default) or sqs. */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(String type, Sqs sqs) {

    public record Sqs(String queueName, boolean createQueue, int waitTimeSeconds) {
    }
}
