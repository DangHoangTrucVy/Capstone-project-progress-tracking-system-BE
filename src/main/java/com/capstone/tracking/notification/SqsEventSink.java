package com.capstone.tracking.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Queues events on SQS; {@link SqsEventConsumer} picks them up asynchronously. */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "sqs")
public class SqsEventSink implements EventSink {

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsEventSink(SqsClient sqs, ObjectMapper objectMapper, SqsQueue queue) {
        this.sqs = sqs;
        this.objectMapper = objectMapper;
        this.queueUrl = queue.url();
    }

    @Override
    public void send(DomainEvent event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.type(), e);
        }
    }
}
