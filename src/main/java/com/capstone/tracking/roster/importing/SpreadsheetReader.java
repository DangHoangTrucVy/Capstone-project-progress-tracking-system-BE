package com.capstone.tracking.roster.importing;

import com.capstone.tracking.common.exception.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads the training department's files: .xlsx, .xls or CSV (UTF-8, comma or semicolon separated). Headers may be
 * Vietnamese or English, with or without accents, in any case; they are mapped to the column keys below.
 */
public final class SpreadsheetReader {

    public static final String CODE = "code";
    public static final String NAME = "name";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String TERM = "term";
    public static final String MAJOR = "major";
    public static final String SUBJECT = "subject";
    public static final String ELIGIBILITY = "eligibility";
    public static final String REASON = "reason";

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        alias(CODE, "mssv", "ma sv", "ma so sv", "ma so sinh vien", "ma sinh vien", "student code", "studentcode",
                "student id", "roll number");
        alias(NAME, "ho ten", "ho va ten", "ten", "full name", "fullname", "name");
        alias(EMAIL, "email", "e-mail", "mail");
        alias(PHONE, "so dien thoai", "sdt", "dien thoai", "phone", "phone number");
        alias(TERM, "ky", "ky hoc", "ky hoc hien tai", "ky hien tai", "term", "current term", "semester number");
        alias(MAJOR, "nganh", "nganh hoc", "major");
        alias(SUBJECT, "mon", "ma mon", "mon hoc", "subject", "subject code");
        alias(ELIGIBILITY, "dieu kien", "du dieu kien", "eligible", "eligibility");
        alias(REASON, "ly do", "reason", "ghi chu", "note");
    }

    /** One data row: its 1-based row number in the file and its cells by column key. */
    public record Line(int rowNumber, Map<String, String> cells) {
        public String get(String key) {
            String v = cells.get(key);
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    /** The data rows (blank rows skipped) and which known columns the header has. */
    public record Table(List<String> columns, List<Line> lines) {
        public boolean has(String key) {
            return columns.contains(key);
        }
    }

    public static Table read(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BadRequestException("The file is empty");
        }
        List<List<String>> rows = isExcel(data) ? readExcel(data)
                : readCsv(new String(data, StandardCharsets.UTF_8).replace("﻿", ""));
        if (rows.isEmpty()) {
            throw new BadRequestException("The file is empty");
        }
        Map<Integer, String> keyByIndex = new LinkedHashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String key = ALIASES.get(fold(header.get(i)));
            if (key != null && !keyByIndex.containsValue(key)) {
                keyByIndex.put(i, key);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            Map<String, String> cells = new HashMap<>();
            keyByIndex.forEach((index, key) -> cells.put(key, index < row.size() ? row.get(index) : ""));
            lines.add(new Line(r + 1, cells));
        }
        return new Table(List.copyOf(keyByIndex.values()), lines);
    }

    /** Lowercase, no accents, đ -> d, separators -> single spaces: "Mã_số SV" -> "ma so sv". */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT)
                .replaceAll("[_\\-.:()]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static void alias(String key, String... names) {
        for (String name : names) {
            ALIASES.put(name, key);
        }
    }

    /** .xlsx is a zip archive ("PK\3\4"); .xls (Excel 97-2003) is an OLE2 compound file (D0 CF 11 E0). */
    private static boolean isExcel(byte[] data) {
        boolean xlsx = data.length > 3 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        boolean xls = data.length > 3 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF
                && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0;
        return xlsx || xls;
    }

    /** The first sheet, every cell read as the text Excel shows (keeps leading zeros of codes and phones stored as text). */
    private static List<List<String>> readExcel(byte[] data) {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(cells);
            }
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException("Cannot read the Excel file: " + e.getMessage());
        }
        return rows;
    }

    /** RFC 4180: quoted fields may hold separators, doubled quotes and line breaks. */
    private static List<List<String>> readCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',' || ch == ';') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private SpreadsheetReader() {
    }
}
