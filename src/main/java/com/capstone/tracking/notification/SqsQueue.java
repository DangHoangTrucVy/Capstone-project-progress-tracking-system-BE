package com.capstone.tracking.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/** Resolves the events queue URL once at startup, creating the queue when allowed (emulators, dev accounts). */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "sqs")
public class SqsQueue {

    private final String url;

    public SqsQueue(SqsClient sqs, MessagingProperties properties) {
        MessagingProperties.Sqs config = properties.sqs();
        String resolved;
        try {
            resolved = sqs.getQueueUrl(b -> b.queueName(config.queueName())).queueUrl();
        } catch (QueueDoesNotExistException e) {
            if (!config.createQueue()) {
                throw e;
            }
            resolved = sqs.createQueue(b -> b.queueName(config.queueName())).queueUrl();
            log.info("Created SQS queue {}", config.queueName());
        }
        this.url = resolved;
    }

    public String url() {
        return url;
    }
}
