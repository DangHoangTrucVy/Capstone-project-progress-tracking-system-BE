package com.capstone.tracking.semester;

/**
 * The four phases of a semester (3.5). Every transition is the Moderator's action (3.6); round 1 and round 2
 * finalization are actions, not phases.
 * <ul>
 *   <li>OPENING ("Mở học kỳ"): the Admin imports students and assigns the Moderator; the Moderator sets the rules, the
 *       deadline and creates rooms. Students only view and edit their profile.</li>
 *   <li>FORMING ("Lập nhóm"): students join and leave rooms freely until the deadline; at the deadline the rooms lock
 *       automatically, the Moderator disbands rooms below their minimum, places the remaining students and finalizes
 *       round 1.</li>
 *   <li>SUPPLEMENT ("Bổ sung"): Leaders invite the few students still without a group; the Moderator finalizes round 2.</li>
 *   <li>CLOSED ("Kết thúc ghép nhóm"): every remaining room became a coded group; view and export only, never reopened.</li>
 * </ul>
 */
public enum SemesterPhase {
    OPENING,
    FORMING,
    SUPPLEMENT,
    CLOSED;

    public String label() {
        return switch (this) {
            case OPENING -> "Mở học kỳ";
            case FORMING -> "Lập nhóm";
            case SUPPLEMENT -> "Bổ sung";
            case CLOSED -> "Kết thúc ghép nhóm";
        };
    }
}
