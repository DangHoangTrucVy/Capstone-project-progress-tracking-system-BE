package com.capstone.tracking.roster.importing;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.roster.Eligibility;
import java.math.BigDecimal;

/** Training-department confirmation is authoritative; absent evidence remains UNDECIDED. */
public record ImportedEligibility(Eligibility value, String reason) {
    public static ImportedEligibility optional(SpreadsheetReader.Line line) {
        String text = line.get(SpreadsheetReader.ELIGIBILITY);
        String percentage = line.get(SpreadsheetReader.CREDITS_PERCENT);
        if (text == null && percentage == null) return null;
        Eligibility value = text == null ? Eligibility.UNDECIDED : parse(text);
        String reason = line.get(SpreadsheetReader.REASON);
        if (percentage != null) {
            BigDecimal percent;
            try {
                percent = new BigDecimal(percentage.replace("%", "").trim());
            } catch (NumberFormatException ex) {
                throw new BadRequestException("Sai tỷ lệ tín chỉ: cần số từ 0 đến 100");
            }
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BadRequestException("Sai tỷ lệ tín chỉ: cần số từ 0 đến 100");
            }
            if (percent.compareTo(BigDecimal.valueOf(80)) < 0) {
                value = Eligibility.INELIGIBLE;
                reason = "Hoàn thành dưới 80% tín chỉ (" + percent.toPlainString() + "%)"
                        + (reason == null ? "" : ": " + reason);
            } else if (text == null) {
                // Credit completion alone does not prove all other eligibility conditions.
                value = Eligibility.UNDECIDED;
            }
        }
        if (reason != null && reason.length() > 500) throw new BadRequestException("Lý do tối đa 500 ký tự");
        return new ImportedEligibility(value, reason);
    }

    public static Eligibility parse(String text) {
        return switch (SpreadsheetReader.fold(text)) {
            case "du", "du dieu kien", "co", "yes", "true", "1", "eligible" -> Eligibility.ELIGIBLE;
            case "khong du", "khong du dieu kien", "khong", "no", "false", "0", "ineligible", "not eligible", "x" -> Eligibility.INELIGIBLE;
            case "chua ket luan", "undecided" -> Eligibility.UNDECIDED;
            default -> throw new BadRequestException("Sai giá trị điều kiện: " + text);
        };
    }
}
