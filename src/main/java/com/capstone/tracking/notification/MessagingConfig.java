package com.capstone.tracking.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    /** Scheduling is only needed for the SQS poller. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "sqs")
    static class SqsPolling {
    }
}
