package com.capstone.tracking.roster;

/**
 * "Điều kiện" (5.1). A newly imported student is UNDECIDED and may form groups; ELIGIBLE only when the data confirms
 * it; INELIGIBLE students sign in but only view.
 */
public enum Eligibility {
    UNDECIDED,
    ELIGIBLE,
    INELIGIBLE
}
