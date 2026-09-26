package com.capstone.tracking.scheduling.dto;

import java.io.Serializable;
import java.util.List;

/** Cacheable form of one page of slot search results; the controller turns it back into a Spring Page. */
public record SlotPage(List<SlotResponse> content, long totalElements) implements Serializable {
}
