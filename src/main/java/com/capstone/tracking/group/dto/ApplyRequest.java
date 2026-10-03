package com.capstone.tracking.group.dto;

import jakarta.validation.constraints.Size;

public record ApplyRequest(@Size(max = 1000) String message) {
}
