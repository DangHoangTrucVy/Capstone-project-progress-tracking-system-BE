package com.capstone.tracking.group.dto;

import com.capstone.tracking.group.VoteType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VoteRequest(@NotNull VoteType vote, @Size(max = 1000) String comment) {
}
