package com.capstone.tracking.subject;

/**
 * Phases of a formation scope (3.4).
 * <ul>
 *   <li>PREPARING: the list is being imported; students only view and edit their profile.</li>
 *   <li>OPEN: from the common opening date; students create groups, apply and invite ("trước deadline").</li>
 *   <li>LOCKED: from the scope's deadline ("sau deadline"); the Moderator arranges the remaining students.</li>
 *   <li>FINALIZED: the Moderator finalized the list for handover; view and export only (and Leader transfer).</li>
 * </ul>
 */
public enum ScopePhase {
    PREPARING,
    OPEN,
    LOCKED,
    FINALIZED
}
