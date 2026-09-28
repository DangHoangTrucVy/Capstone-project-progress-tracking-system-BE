package com.capstone.tracking.review;

/** Bước 5.3: how the closed council (Review 3) sorts a group for the final defense. */
public enum ClosedCouncilOutcome {
    /** Nhóm ổn, không cần chỉnh sửa -> được ra Bảo vệ lần 1 ngay. */
    READY_FOR_DEFENSE_1("Đạt, được ra Bảo vệ lần 1"),
    /** Nhóm cần chỉnh sửa -> hoàn thiện theo góp ý mới được ra Bảo vệ lần 1. */
    REVISE_BEFORE_DEFENSE_1("Cần chỉnh sửa theo góp ý trước khi ra Bảo vệ lần 1"),
    /** Nhóm chưa kịp / chưa đạt -> đẩy xuống Bảo vệ lần 2. */
    DEFER_TO_DEFENSE_2("Chưa đạt, chuyển xuống Bảo vệ lần 2");

    private final String label;

    ClosedCouncilOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
