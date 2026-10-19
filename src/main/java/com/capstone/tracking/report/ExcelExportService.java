package com.capstone.tracking.report;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.roster.Eligibility;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.NoGroupReason;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.GroupMember;
import com.capstone.tracking.user.User;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The handover file (10): two sheets, one student per row, no merged cells. "Danh sách nhóm" lists every member of
 * every current group, "Chưa có nhóm" the students without one (the ineligible included). Each sheet starts with the
 * semester, subject, major, export time and the finalize state ("Đã chốt lúc …" / "Chưa chốt", "Đã mở lại lúc …",
 * "Thay đổi gần nhất sau chốt: …"). Codes and phone numbers are text (leading zeros kept); headers have a filter and
 * stay frozen. Profiles (skills, strengths...) are never exported.
 */
@Service
@RequiredArgsConstructor
public class ExcelExportService {

    public static final String GROUPS_SHEET = "Danh sách nhóm";
    public static final String NO_GROUP_SHEET = "Chưa có nhóm";

    private static final String[] GROUP_HEADERS = {"Học kỳ", "Môn", "Ngành", "Mã nhóm", "Tên nhóm", "Sĩ số",
            "Trạng thái nhóm", "Mã số", "Họ tên", "Email", "Số điện thoại", "Vai trò"};
    private static final String[] NO_GROUP_HEADERS = {"Học kỳ", "Môn", "Ngành", "Mã số", "Họ tên", "Email",
            "Số điện thoại", "Điều kiện", "Lý do chưa có nhóm", "Ghi chú điều phối"};
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(VnTime.ZONE);

    private final Snapshot snapshot;
    private final ScopeRules rules;

    public record ExcelFile(String fileName, byte[] content) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExcelFile export(String semesterCode, UUID subjectId, UUID majorId, User viewer) {
        Snapshot.Data data = snapshot.take(semesterCode, subjectId, majorId, viewer, rules::deadline);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(wb);
            List<String> header = headerLines(data);
            writeGroups(wb.createSheet(GROUPS_SHEET), styles, header, data);
            writeNoGroup(wb.createSheet(NO_GROUP_SHEET), styles, header, data);
            wb.write(out);
            return new ExcelFile(fileName(data), out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ sheets

    private void writeGroups(Sheet sheet, Styles styles, List<String> header, Snapshot.Data data) {
        int r = writeHeader(sheet, styles, header);
        int tableStart = r;
        writeColumns(sheet.createRow(r++), styles, GROUP_HEADERS);
        List<Snapshot.ScopeData> scopes = sortedScopes(data);
        for (Snapshot.ScopeData s : scopes) {
            List<Snapshot.GroupData> groups = new ArrayList<>(s.groups());
            groups.sort(Comparator.comparing(g -> g.group().getCode()));
            for (Snapshot.GroupData g : groups) {
                List<GroupMember> members = new ArrayList<>(g.members());
                members.sort(Comparator.comparing((GroupMember m) -> !m.isLeader())
                        .thenComparing(m -> nullToEmpty(m.getUser().getStudentCode())));
                for (GroupMember m : members) {
                    Row row = sheet.createRow(r++);
                    int c = 0;
                    text(row, c++, data.semester().getCode(), styles);
                    text(row, c++, s.scope().getSubject().getName(), styles);
                    text(row, c++, s.scope().getMajor().getName(), styles);
                    text(row, c++, g.group().getCode(), styles);
                    text(row, c++, g.group().getName(), styles);
                    row.createCell(c++).setCellValue(g.members().size());
                    text(row, c++, g.state(), styles);
                    text(row, c++, m.getUser().getStudentCode(), styles);
                    text(row, c++, m.getUser().getFullName(), styles);
                    text(row, c++, m.getUser().getEmail(), styles);
                    text(row, c++, m.getUser().getPhone(), styles);
                    text(row, c, m.isLeader() ? "Leader" : "Member", styles);
                }
            }
        }
        finish(sheet, tableStart, r, GROUP_HEADERS.length);
    }

    private void writeNoGroup(Sheet sheet, Styles styles, List<String> header, Snapshot.Data data) {
        int r = writeHeader(sheet, styles, header);
        int tableStart = r;
        writeColumns(sheet.createRow(r++), styles, NO_GROUP_HEADERS);
        for (Snapshot.ScopeData s : sortedScopes(data)) {
            List<Enrollment> without = s.students().stream()
                    .filter(e -> !s.membershipByEnrollment().containsKey(e.getId()))
                    .sorted(Comparator.comparing(e -> nullToEmpty(e.getUser().getStudentCode())))
                    .toList();
            for (Enrollment e : without) {
                Row row = sheet.createRow(r++);
                int c = 0;
                text(row, c++, data.semester().getCode(), styles);
                text(row, c++, s.scope().getSubject().getName(), styles);
                text(row, c++, s.scope().getMajor().getName(), styles);
                text(row, c++, e.getUser().getStudentCode(), styles);
                text(row, c++, e.getUser().getFullName(), styles);
                text(row, c++, e.getUser().getEmail(), styles);
                text(row, c++, e.getUser().getPhone(), styles);
                text(row, c++, eligibility(e.getEligibility()), styles);
                text(row, c++, reason(e), styles);
                text(row, c, "", styles);
            }
        }
        finish(sheet, tableStart, r, NO_GROUP_HEADERS.length);
    }

    // ------------------------------------------------------------------ header block

    private List<String> headerLines(Snapshot.Data data) {
        List<String> lines = new ArrayList<>();
        lines.add("Học kỳ: " + data.semester().getCode() + " (" + data.semester().getName() + ")");
        lines.add("Môn: " + (data.subject() == null ? "Tất cả" : data.subject().getCode() + " - " + data.subject().getName()));
        lines.add("Ngành: " + (data.majorId() == null ? "Tất cả"
                : data.scopes().isEmpty() ? "" : data.scopes().get(0).scope().getMajor().getName()));
        lines.add("Thời điểm xuất: " + VnTime.format(data.takenAt()));
        for (Snapshot.ScopeData s : sortedScopes(data)) {
            String prefix = data.scopes().size() == 1 ? "" : s.scope().getSubject().getCode() + " / "
                    + s.scope().getMajor().getCode() + ": ";
            lines.add(prefix + finalizeState(s.scope()));
        }
        return lines;
    }

    private static String finalizeState(FormationScope scope) {
        StringBuilder state = new StringBuilder(scope.getFinalizedAt() != null
                ? "Đã chốt lúc " + VnTime.format(scope.getFinalizedAt()) : "Chưa chốt");
        if (scope.getFinalizedAt() == null && scope.getReopenedAt() != null) {
            state.append("; Đã mở lại lúc ").append(VnTime.format(scope.getReopenedAt()));
        }
        if (scope.getFinalizedAt() != null && scope.getLastChangeAfterFinalize() != null) {
            state.append("; Thay đổi gần nhất sau chốt: ").append(scope.getLastChangeAfterFinalize());
        }
        return state.toString();
    }

    private static String fileName(Snapshot.Data data) {
        String subject = data.subject() == null ? "TatCa" : safe(data.subject().getCode());
        String major = data.majorId() == null || data.scopes().isEmpty() ? "TatCa"
                : safe(data.scopes().get(0).scope().getMajor().getCode());
        return "Danh_sach_ghep_nhom_" + data.semester().getCode() + "_" + subject + "_" + major + "_"
                + FILE_STAMP.format(data.takenAt()) + ".xlsx";
    }

    // ------------------------------------------------------------------ helpers

    private static List<Snapshot.ScopeData> sortedScopes(Snapshot.Data data) {
        return data.scopes().stream()
                .sorted(Comparator.comparing((Snapshot.ScopeData s) -> s.scope().getMajor().getName())
                        .thenComparing(s -> s.scope().getSubject().getCode()))
                .toList();
    }

    private static int writeHeader(Sheet sheet, Styles styles, List<String> lines) {
        int r = 0;
        for (String line : lines) {
            Cell cell = sheet.createRow(r++).createCell(0);
            cell.setCellValue(line);
            cell.setCellStyle(styles.title);
        }
        return r + 1;
    }

    private static void writeColumns(Row row, Styles styles, String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }
    }

    private static void finish(Sheet sheet, int headerRow, int endRow, int columns) {
        sheet.setAutoFilter(new CellRangeAddress(headerRow, Math.max(headerRow, endRow - 1), 0, columns - 1));
        sheet.createFreezePane(0, headerRow + 1);
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, 20 * 256);
        }
    }

    /** Every value is written as text, so codes and phone numbers keep their leading zeros. */
    private static void text(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(styles.text);
        cell.setCellValue(value == null ? "" : value);
    }

    private static String eligibility(Eligibility e) {
        return switch (e) {
            case ELIGIBLE -> "Đủ điều kiện";
            case INELIGIBLE -> "Không đủ điều kiện";
            case UNDECIDED -> "Chưa kết luận";
        };
    }

    /** Only when there is a known cause (10). */
    private static String reason(Enrollment e) {
        if (e.isIneligible()) {
            return "Không đủ điều kiện" + (e.getEligibilityReason() == null ? "" : ": " + e.getEligibilityReason());
        }
        NoGroupReason reason = e.getNoGroupReason();
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case GROUP_DISSOLVED -> "Nhóm bị giải tán";
            case INELIGIBLE -> "Không đủ điều kiện";
            case REMOVED_BY_MODERATOR -> "Moderator đưa ra khỏi nhóm";
            case LEFT_GROUP -> "Đã rời nhóm";
        };
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9-]", "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle text;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            title = wb.createCellStyle();
            title.setFont(bold);
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
        }
    }
}
