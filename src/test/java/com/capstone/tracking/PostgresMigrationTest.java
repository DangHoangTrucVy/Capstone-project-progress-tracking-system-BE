package com.capstone.tracking;

import com.capstone.tracking.auth.AuthService;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Flyway migrations (V1..V7 + dev seed) applied to a real PostgreSQL 16, with Hibernate validating every entity
 * against the result — what `docker compose up` does, without Docker. The workflow tests have *PostgresTest
 * variants that run their flows on this database too.
 */
@SpringBootTest
@ActiveProfiles("test")
@PostgresTest
class PostgresMigrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService authService;

    @Test
    void everyMigrationAppliesOnPostgres() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null order by installed_rank",
                String.class);
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "10", "11", "12", "13", "14", "1000", "1001", "1002", "1003");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class))
                .isZero();
    }

    @Test
    void v7ReplacedTheRoleAndGroupStatusChecks() {
        assertThat(jdbc.queryForObject("select role from users where email = 'hoidong@fpt.edu.vn'", String.class))
                .isEqualTo("COUNCIL");
        assertThatThrownBy(() -> jdbc.update("update users set role = 'DEAN' where email = 'hoidong@fpt.edu.vn'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update student_groups set status = 'FAILED' where group_code = 'SE-F26-04'");
        jdbc.update("update student_groups set status = 'FORMED' where group_code = 'SE-F26-04'");
        assertThatThrownBy(() -> jdbc.update("update users set campus = 'HUE' where email = 'hoidong@fpt.edu.vn'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seededDevCouncilAccountsCanSignInWithPassword() {
        for (int i = 1; i <= 5; i++) {
            String email = "council0" + i + "@fpt.edu.vn";
            assertThat(authService.login(new LoginRequest(email, "Council@123")).user().role().name())
                    .isEqualTo("COUNCIL");
        }
    }
}
