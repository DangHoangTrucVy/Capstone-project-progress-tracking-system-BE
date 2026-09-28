package com.capstone.tracking.proposal;

/** Giai đoạn 2: Leader submits -> Instructor picks one topic -> Council approves or rejects. */
public enum ProposalStatus {
    /** Submitted by the Leader, waiting for the supervisor's pre-review (bước 2.1 -> 2.2). */
    PENDING_INSTRUCTOR,
    /** The supervisor forwarded one topic; the Council has until councilDeadline (bước 2.3 / 2.4). */
    PENDING_COUNCIL,
    APPROVED,
    REJECTED
}
