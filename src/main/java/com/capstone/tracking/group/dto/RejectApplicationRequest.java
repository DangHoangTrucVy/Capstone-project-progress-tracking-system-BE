package com.capstone.tracking.group.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Size;

/** Why the Leader turns an application down; forwarded to the applicant. */
public record RejectApplicationRequest(
        @JsonAlias({"rejectReason", "rejectionReason", "note", "message"})
        @Size(max = 1000) String reason
) {
}
