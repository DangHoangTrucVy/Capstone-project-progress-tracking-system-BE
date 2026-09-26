package com.capstone.tracking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Long-polls the events queue and hands each message to {@link NotificationHandler}. A message is deleted only
 * after it was handled; a failure leaves it on the queue so SQS redelivers it after the visibility timeout.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "sqs")
public class SqsEventConsumer {

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final NotificationHandler handler;
    private final String queueUrl;
    private final int waitTimeSeconds;

    public SqsEventConsumer(SqsClient sqs, ObjectMapper objectMapper, NotificationHandler handler, SqsQueue queue,
                            MessagingProperties properties) {
        this.sqs = sqs;
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.queueUrl = queue.url();
        this.waitTimeSeconds = properties.sqs().waitTimeSeconds();
    }

    @Scheduled(fixedDelayString = "${app.messaging.sqs.poll-delay-ms:1000}")
    public void poll() {
        var messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds(waitTimeSeconds)).messages();
        for (Message message : messages) {
            try {
                handler.handle(objectMapper.readValue(message.body(), DomainEvent.class));
                sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            } catch (Exception e) {
                log.error("Failed to handle SQS message {}; it will be redelivered", message.messageId(), e);
            }
        }
    }
}
