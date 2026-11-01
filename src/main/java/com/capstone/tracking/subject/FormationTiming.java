package com.capstone.tracking.subject;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Main backend timing defaults; deployments may override them through environment settings. */
@Component
public class FormationTiming {
    private final int defaultRequestTtlMinutes;
    private final int minDeadlineNoticeMinutes;

    public FormationTiming(
            @Value("${app.formation.default-request-ttl-minutes:15}") int defaultRequestTtlMinutes,
            @Value("${app.formation.min-deadline-notice-minutes:10}") int minDeadlineNoticeMinutes) {
        if (defaultRequestTtlMinutes < 1 || defaultRequestTtlMinutes > 20160 || minDeadlineNoticeMinutes < 1) {
            throw new IllegalArgumentException("Formation durations must be positive; request TTL cannot exceed 14 days");
        }
        this.defaultRequestTtlMinutes = defaultRequestTtlMinutes;
        this.minDeadlineNoticeMinutes = minDeadlineNoticeMinutes;
    }

    public int defaultRequestTtlMinutes() { return defaultRequestTtlMinutes; }
    public int minDeadlineNoticeMinutes() { return minDeadlineNoticeMinutes; }
    public Duration minimumDeadlineNotice() { return Duration.ofMinutes(minDeadlineNoticeMinutes); }
}
