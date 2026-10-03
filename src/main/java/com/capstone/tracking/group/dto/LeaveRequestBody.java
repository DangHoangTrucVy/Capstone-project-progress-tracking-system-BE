package com.capstone.tracking.group.dto;

import jakarta.validation.constraints.Size;

public record LeaveRequestBody(@Size(max = 1000) String reason) {
}
