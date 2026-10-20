package com.capstone.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.capstone.tracking.auth.AuthService;
import com.capstone.tracking.auth.dto.LoginRequest;
import com.capstone.tracking.support.PostgresTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The Flyway migrations (V1..V18 + dev seed) applied to a real PostgreSQL 16, with Hibernate validating every entity
 * against the result — what `docker compose up` does, without Docker.
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
        assertThat(versions).contains("1", "2", "8", "17", "18", "19", "20", "2000", "2001");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class))
                .isZero();
    }

    @Test
    void v18DroppedTheCapstoneTrackingDomainAndKeptAccounts() {
        for (String gone : List.of("topics", "schedule_slots", "meeting_sessions", "review_sessions", "defense_sessions",
                "semester_calendars", "group_join_requests")) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = ?",
                    Integer.class, gone)).as(gone).isZero();
        }
        assertThat(jdbc.queryForObject("select role from users where email = 'admin@fpt.edu.vn'", String.class))
                .isEqualTo("ADMIN");
        assertThatThrownBy(() -> jdbc.update("update users set role = 'COUNCIL' where email = 'admin@fpt.edu.vn'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseKeepsOneGroupPerStudentAndOneLeaderPerGroup() {
        // se180002 is a member of Team Alpha: a second open membership in the same subject is refused.
        assertThatThrownBy(() -> jdbc.update("""
                insert into group_members (id, group_id, enrollment_id, user_id, is_leader, joined_at, created_at, updated_at)
                values (gen_random_uuid(), '78000000-0000-0000-0000-000000000002', '77000000-0000-0000-0000-000000000002',
                        '73000000-0000-0000-0000-000000000002', false, now(), now(), now())"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update group_members set is_leader = true "
                + "where id = '79000000-0000-0000-0000-000000000002'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into semesters (code, name, start_date, end_date, created_at, updated_at) "
                + "values ('Fall2027', 'x', date '2027-09-01', date '2027-12-01', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seededModeratorSignsInWithPassword() {
        assertThat(authService.login(new LoginRequest("lecturer01@fpt.edu.vn", "Admin@123")).accessToken()).isNotBlank();
        assertThat(jdbc.queryForObject("select lecturer_imported from users where email = 'lecturer01@fpt.edu.vn'", Boolean.class))
                .isTrue();
    }

    @Test
    void theDatabaseRefusesOtherSubjectsAndASecondCapstoneInASemester() {
        assertThatThrownBy(() -> jdbc.update("update subjects set code = 'EXE' where semester_code = 'FA26'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update subjects set max_group_size = 6 where semester_code = 'FA26'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into subjects (id, semester_code, code, name, target_term, created_by, created_at, updated_at)
                select gen_random_uuid(), semester_code, code, name, target_term, created_by, now(), now()
                from subjects where semester_code = 'FA26'"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
