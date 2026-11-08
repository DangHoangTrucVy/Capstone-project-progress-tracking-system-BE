package com.capstone.tracking.semester;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Timing defaults. The requirement fixes 48 hours for both the request lifetime and the minimum notice of a new
 * deadline; deployments (demos) may shorten them through environment settings, in minutes.
 */
@Component
public class FormationTiming {
    private final int defaultRequestTtlMinutes;
    private final int minDeadlineNoticeMinutes;

    public FormationTiming(
            @Value("${app.formation.default-request-ttl-minutes:2880}") int defaultRequestTtlMinutes,
            @Value("${app.formation.min-deadline-notice-minutes:2880}") int minDeadlineNoticeMinutes) {
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
