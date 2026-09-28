package com.capstone.tracking.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Notification/email text is read by Vietnamese users, so times are shown in Vietnam time. */
public final class VnTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZONE);

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    private VnTime() {
    }
}
