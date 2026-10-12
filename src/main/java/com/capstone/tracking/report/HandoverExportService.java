package com.capstone.tracking.report;

import com.capstone.tracking.group.GroupMember;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupRepository;
import com.capstone.tracking.group.dto.JoinRequestResponse;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Xuất danh sách bàn giao": an Excel workbook with the groups and their members ({@value #GROUPS_SHEET}) and the
 * active students who are in no group ({@value #NO_GROUP_SHEET}), for one semester or for all of them.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HandoverExportService {

    public static final String GROUPS_SHEET = "Danh_sach_nhom";
    public static final String NO_GROUP_SHEET = "Chua_co_nhom";

    private static final String[] GROUP_HEADERS = {"STT", "Mã nhóm", "Học kỳ", "Đề tài", "GVHD", "Email GVHD",
            "Trạng thái nhóm", "Khóa danh sách", "Duyệt danh sách", "Số thành viên", "MSSV", "Họ tên", "Email",
            "Vai trò"};
    private static final String[] NO_GROUP_HEADERS = {"STT", "MSSV", "Họ tên", "Email", "Campus", "Đủ điều kiện",
            "Lý do", "Trạng thái tài khoản"};

    private final StudentGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;

    /** @param semester only this semester's groups (and students without a group in it); null or blank = all */
    public byte[] export(String semester) {
        boolean all = semester == null || semester.isBlank();
        String term = all ? "" : semester.trim();
        List<StudentGroup> groups = groupRepository.findAll(Sort.by("semester", "groupCode")).stream()
                .filter(g -> all || term.equalsIgnoreCase(g.getSemester()))
                .toList();
        Map<UUID, List<GroupMember>> members = memberRepository.findAllWithUserByStatus(MemberStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        List<User> withoutGroup = userRepository.findStudentsWithoutGroup(List.of(Role.STUDENT, Role.GROUP_LEADER),
                UserStatus.ACTIVE, MemberStatus.ACTIVE, all, term);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            writeGroups(workbook.createSheet(GROUPS_SHEET), header, groups, members);
            writeWithoutGroup(workbook.createSheet(NO_GROUP_SHEET), header, withoutGroup);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build the handover workbook", e);
        }
    }

    /** One row per member; the group columns repeat so the sheet can be filtered. An empty group still gets a row. */
    private void writeGroups(Sheet sheet, CellStyle header, List<StudentGroup> groups,
                             Map<UUID, List<GroupMember>> membersByGroup) {
        writeHeader(sheet, header, GROUP_HEADERS);
        int r = 1;
        int stt = 1;
        for (StudentGroup g : groups) {
            List<GroupMember> members = membersByGroup.getOrDefault(g.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(GroupMember::isLeader).reversed()
                            .thenComparing(m -> m.getUser().getFullName(), Comparator.nullsLast(String::compareTo)))
                    .toList();
            List<GroupMember> rows = members.isEmpty() ? Collections.singletonList(null) : members;
            for (GroupMember m : rows) {
                User u = m == null ? null : m.getUser();
                write(sheet.createRow(r++),
                        stt,
                        g.getGroupCode(),
                        g.getSemester(),
                        g.getTopic() == null ? "" : g.getTopic().getTitle(),
                        g.getSupervisor() == null ? "" : g.getSupervisor().getFullName(),
                        g.getSupervisor() == null ? "" : g.getSupervisor().getEmail(),
                        g.getStatus() == null ? "" : g.getStatus().name(),
                        g.isLocked() ? "Đã khóa" : "Chưa khóa",
                        g.getRosterStatus() == null ? "" : g.getRosterStatus().name(),
                        members.size(),
                        u == null ? "" : JoinRequestResponse.studentCodeOf(u),
                        u == null ? "" : u.getFullName(),
                        u == null ? "" : u.getEmail(),
                        m == null ? "" : (m.isLeader() ? "Leader" : "Thành viên"));
            }
            stt++;
        }
        finish(sheet, GROUP_HEADERS.length);
    }

    private void writeWithoutGroup(Sheet sheet, CellStyle header, List<User> students) {
        writeHeader(sheet, header, NO_GROUP_HEADERS);
        int r = 1;
        for (User u : students) {
            write(sheet.createRow(r), r,
                    JoinRequestResponse.studentCodeOf(u),
                    u.getFullName(),
                    u.getEmail(),
                    u.getCampus() == null ? "" : u.getCampus().name(),
                    u.isEligible() ? "Đủ" : "Chưa đủ",
                    u.getIneligibleReason() == null ? "" : u.getIneligibleReason(),
                    u.getStatus().name());
            r++;
        }
        finish(sheet, NO_GROUP_HEADERS.length);
    }

    private static void writeHeader(Sheet sheet, CellStyle style, String[] headers) {
        Row row = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            row.createCell(c).setCellValue(headers[c]);
            row.getCell(c).setCellStyle(style);
        }
    }

    private static void write(Row row, Object... values) {
        for (int c = 0; c < values.length; c++) {
            if (values[c] instanceof Number n) {
                row.createCell(c).setCellValue(n.doubleValue());
            } else {
                row.createCell(c).setCellValue(values[c] == null ? "" : values[c].toString());
            }
        }
    }

    private static void finish(Sheet sheet, int columns) {
        sheet.createFreezePane(0, 1);
        if (sheet.getLastRowNum() > 0) {
            sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, columns - 1));
        }
        for (int c = 0; c < columns; c++) {
            // Width from the content (no autoSizeColumn: it needs AWT fonts, missing in slim server images).
            int width = 8;
            for (Row row : sheet) {
                if (row.getCell(c) != null) {
                    width = Math.max(width, row.getCell(c).toString().length() + 2);
                }
            }
            sheet.setColumnWidth(c, Math.min(width, 60) * 256);
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
