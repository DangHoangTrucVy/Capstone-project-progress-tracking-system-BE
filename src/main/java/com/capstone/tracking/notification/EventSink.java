package com.capstone.tracking.notification;

/** Where committed domain events go: handled in-process (default) or queued on SQS ({@code app.messaging.type}). */
public interface EventSink {

    void send(DomainEvent event);
}
