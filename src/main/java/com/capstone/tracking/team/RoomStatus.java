package com.capstone.tracking.team;

/**
 * What everyone sees of a room (6.3), computed from its members, its flags and the semester's phase.
 * <ul>
 *   <li>EMPTY ("Trống"): nobody in it; kept, never deleted.</li>
 *   <li>OPEN ("Đang ghép"): has a Leader; students join and leave freely.</li>
 *   <li>FULL ("Đủ"): at the semester's maximum size.</li>
 *   <li>LOCKED ("Khóa"): the Leader locked it; joining takes a request the Leader accepts.</li>
 *   <li>FINALIZE_REQUESTED ("Đã gửi chốt"): waiting for the Moderator's approval.</li>
 *   <li>DEADLINE_LOCKED ("Bị khóa theo deadline"): past the deadline and not approved; only the Moderator changes it.</li>
 *   <li>FINALIZED ("Đã thành nhóm"): approved, or formation ended; it has a group code.</li>
 *   <li>CLOSED: an empty room closed when formation ended.</li>
 * </ul>
 */
public enum RoomStatus {
    EMPTY,
    OPEN,
    FULL,
    LOCKED,
    FINALIZE_REQUESTED,
    DEADLINE_LOCKED,
    FINALIZED,
    CLOSED;

    public String label() {
        return switch (this) {
            case EMPTY -> "Trống";
            case OPEN -> "Đang ghép";
            case FULL -> "Đủ";
            case LOCKED -> "Khóa";
            case FINALIZE_REQUESTED -> "Đã gửi chốt";
            case DEADLINE_LOCKED -> "Bị khóa theo deadline";
            case FINALIZED -> "Đã chốt";
            case CLOSED -> "Đã đóng";
        };
    }
}
