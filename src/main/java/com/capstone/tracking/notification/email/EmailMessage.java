package com.capstone.tracking.notification.email;

import java.util.List;

public record EmailMessage(List<String> to, List<String> cc, String subject, String body) {
}
