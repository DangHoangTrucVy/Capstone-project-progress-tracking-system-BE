package com.capstone.tracking.eligibility;

import com.capstone.tracking.WorkflowTestSupport;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shapes the FE (and people) actually send to the eligibility import, JSON and file. */
class EligibilityImportFormatsIntegrationTest extends WorkflowTestSupport {

    private static final String URL = "/api/v1/eligibility/import";

    private User studentWithCode(String tag, String code) {
        User u = user(tag, Role.STUDENT);
        u.setStudentCode(code);
        return userRepository.save(u);
    }

    @Test
    @DisplayName("A bare JSON array is accepted like {\"students\": [...]}")
    void bareArray() throws Exception {
        User admin = user("ia-admin", Role.ADMIN);
        String email = "ia-new-" + suffix + "@fpt.edu.vn";
        postJson(URL, admin, List.of(Map.of("email", email, "fullName", "Nguyễn Văn Mảng", "studentCode", "IA" + suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.students[0].fullName").value("Nguyễn Văn Mảng"))
                .andExpect(jsonPath("$.students[0].studentCode").value("IA" + suffix));
    }

    @Test
    @DisplayName("Without email, an existing student is matched by MSSV; eligible left out means eligible")
    void studentCodeOnlyForExistingAccount() throws Exception {
        User admin = user("ic-admin", Role.ADMIN);
        User s = studentWithCode("ic-s", "IC" + suffix);
        postJson(URL, admin, Map.of("students", List.of(Map.of("studentCode", "ic" + suffix))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.students[0].email").value(s.getEmail()))
                .andExpect(jsonPath("$.students[0].eligible").value(true))
                .andExpect(jsonPath("$.students[0].action").value("UPDATED"));
        assertThat(userRepository.findById(s.getId()).orElseThrow().isEligible()).isTrue();
        assertThat(userRepository.findById(s.getId()).orElseThrow().getImportedAt()).isNotNull();
    }

    @Test
    @DisplayName("Vietnamese field names are accepted (ho_ten, du_dieu_kien, ly_do, mssv)")
    void vietnameseAliases() throws Exception {
        User admin = user("iv-admin", Role.ADMIN);
        String email = "iv-" + suffix + "@fpt.edu.vn";
        postJson(URL, admin, List.of(Map.of("email", email, "ho_ten", "Trần Thị Bí Danh", "mssv", "IV" + suffix,
                        "du_dieu_kien", false, "ly_do", "Nợ học phí")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagged").value(1))
                .andExpect(jsonPath("$.students[0].fullName").value("Trần Thị Bí Danh"))
                .andExpect(jsonPath("$.students[0].ineligibleReason").value("Nợ học phí"));
    }

    @Test
    @DisplayName("Nothing usable (the old FE placeholder: MSSV unknown, no email): 400 that says why, not '0 created'")
    void nothingImportedIs400() throws Exception {
        User admin = user("in-admin", Role.ADMIN);
        postJson(URL, admin, List.of(Map.of("studentCode", "SE12345-" + suffix)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("No student was imported")))
                .andExpect(jsonPath("$.message").value(containsString("email is required")))
                .andExpect(jsonPath("$.message").value(containsString("SE12345-" + suffix)));
        postJson(URL, admin, List.of(Map.of("email", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("invalid email")));
    }

    @Test
    @DisplayName("Empty list, wrong shape or non-object items: 400")
    void wrongShapes() throws Exception {
        User admin = user("iw-admin", Role.ADMIN);
        postJson(URL, admin, List.of()).andExpect(status().isBadRequest());
        postJson(URL, admin, Map.of("students", List.of())).andExpect(status().isBadRequest());
        postJson(URL, admin, Map.of("foo", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("students")));
        postJson(URL, admin, List.of("x@fpt.edu.vn")).andExpect(status().isBadRequest());
        postJson(URL, admin, List.of(Map.of("email", "x-" + suffix + "@fpt.edu.vn", "eligible", "maybe")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Partly valid lists still import the valid lines and list the others")
    void partlyValid() throws Exception {
        User admin = user("ip-admin", Role.ADMIN);
        postJson(URL, admin, List.of(Map.of("email", "ip-" + suffix + "@fpt.edu.vn"), Map.of("studentCode", "NOPE" + suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.invalid[0]").value(containsString("line 2")));
    }

    @Test
    @DisplayName("Excel 97-2003 (.xls) files are imported like .xlsx")
    void xlsFile() throws Exception {
        User admin = user("ix-admin", Role.ADMIN);
        String email = "ix-" + suffix + "@fpt.edu.vn";
        byte[] xls;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("DS");
            Row h = sheet.createRow(0);
            String[] headers = {"Email", "Họ tên", "MSSV", "Đủ điều kiện", "Lý do"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(email);
            r.createCell(1).setCellValue("Phạm Văn Xls");
            r.createCell(2).setCellValue("0" + suffix.replaceAll("[^0-9]", "1"));
            r.createCell(3).setCellValue("không");
            r.createCell(4).setCellValue("Thiếu tín chỉ");
            wb.write(out);
            xls = out.toByteArray();
        }
        mockMvc.perform(multipart(URL + "/file")
                        .file(new MockMultipartFile("file", "ds.xls", "application/vnd.ms-excel", xls))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.flagged").value(1))
                .andExpect(jsonPath("$.students[0].fullName").value("Phạm Văn Xls"))
                .andExpect(jsonPath("$.students[0].studentCode").value(startsWithZero()));
    }

    private static org.hamcrest.Matcher<String> startsWithZero() {
        return org.hamcrest.Matchers.startsWith("0");
    }

    @Test
    @DisplayName("CSV with only an MSSV column updates existing students; a file without email/mssv says which formats work")
    void csvVariants() throws Exception {
        User admin = user("is-admin", Role.ADMIN);
        User s = studentWithCode("is-s", "IS" + suffix);
        String csv = "mssv,du_dieu_kien,ly_do\nIS" + suffix + ",0,Nợ học phí\n";
        mockMvc.perform(multipart(URL + "/file")
                        .file(new MockMultipartFile("file", "ds.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        assertThat(userRepository.findById(s.getId()).orElseThrow().isEligible()).isFalse();

        mockMvc.perform(multipart(URL + "/file")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(".xls")));
    }

    @Test
    @DisplayName("Broken multipart (no boundary) or no 'file' part: 400, not 500")
    void brokenMultipart() throws Exception {
        User admin = user("ib-admin", Role.ADMIN);
        mockMvc.perform(post(URL + "/file").header("Authorization", bearer(admin))
                        .contentType("multipart/form-data").content("email\nx@fpt.edu.vn\n"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(URL + "/file")
                        .file(new MockMultipartFile("upload", "ds.csv", "text/csv", "email\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_MULTIPART"));
    }
}
