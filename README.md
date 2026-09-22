# Capstone Project Progress Tracking System
*(Student Schedule and Guidance Management System)*

Hệ thống hỗ trợ quản lý lịch đánh giá, theo dõi tiến độ và giám sát hướng dẫn đồ án/khóa luận tốt nghiệp giữa Giảng viên hướng dẫn (Supervisor) và các nhóm sinh viên.

---

## 📌 Tài liệu dự án

- 📄 [**Intent Specification (`intent.md`)**](intent.md): Bối cảnh bài toán, mô hình tham chiếu tương tự Calendly và kết quả kỳ vọng.
- 📐 [**Software Development Blueprint (`blueprint.md`)**](blueprint.md): Bản vẽ kiến trúc & kỹ thuật chi tiết 15 mục (Requirements, Use Cases, User Stories, Data Model, API Contract, NFR, Traceability Matrix).
- 🤖 [**BA Blueprint Agent Framework (`BA-Blueprint-Agent/`)**](BA-Blueprint-Agent/README.md): Bộ khung quản trị chất lượng (Quality Gates), 10 kỹ năng phân tích nghiệp vụ và quy trình đồng bộ GitHub Delivery.

---

## 🎯 Tính năng cốt lõi (Core Capabilities)

1. **Lịch kiểm tra & đánh giá (Assessment Scheduling):** Đặt lịch tự động, kiểm soát sức chứa (capacity), loại bỏ 100% xung đột lịch theo mô hình Calendly.
2. **Quản lý hồ sơ nhóm & nộp Artifacts:** Quản lý tập trung thành viên, đề tài và các sản phẩm bàn giao theo từng mốc.
3. **Ngân hàng câu hỏi & tài liệu đề tài:** Kho học liệu và câu hỏi phản biện phân loại theo chuyên đề do Admin quản lý.
4. **Hỗ trợ trong buổi làm việc & Biên bản họp:** Ghi nhận yêu cầu mới phát sinh (New Requirements) và tự động sinh bản thảo Biên bản cuộc họp (Meeting Minutes) từ ghi chú.
5. **Đánh giá đa chiều (3-Dimensional Evaluation):** Chấm điểm và nhận xét theo 3 tiêu chí: *Topic Fit*, *Product Quality*, và *Communication*.
6. **Báo cáo & Thống kê tiến độ:** Dashboard theo dõi phiên họp, tỷ lệ tham gia và rủi ro chậm trễ theo tuần và theo học kỳ.

---

## 👥 Nhóm phát triển

- **Nhóm:** Group 2
- **Môn học:** SWD / Capstone Project

---

## ⚙️ Backend Service (Spring Boot)

Spring Boot backend cho **Student Schedule and Guidance Management System**
(xem `blueprint.md` để biết đặc tả đầy đủ 15 mục). Đây là nền tảng cho toàn bộ
kế hoạch triển khai 5 sprint (§12): mọi entity trong Data Model (§8) đã có
trong code, Sprint 1 và Sprint 2 có đầy đủ service/controller, và Sprint 3–5
đã có sẵn data layer để việc bổ sung business logic là cộng thêm chứ không
phải refactor lại.

### Stack

- Java 17, Spring Boot 3.3.4, Maven
- Spring Web, Spring Data JPA, Spring Security (stateless JWT)
- PostgreSQL + Flyway migrations
- springdoc-openapi (Swagger UI)
- Lombok
- JUnit 5 + MockMvc + H2 (test profile)

### What's implemented

| Sprint | Area | Endpoints | Status |
|---|---|---|---|
| 1 | Auth | `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me` | Full — register restricted to `@fpt.edu.vn` (configurable), always creates a STUDENT |
| 1 | Users | `POST/GET /api/v1/users`, `GET/PUT /api/v1/users/{id}` | Full — Admin-only |
| 1 | Topics | `POST/GET /api/v1/topics`, `GET/PUT /api/v1/topics/{id}` | Full — create/edit = Admin, read = any role |
| 1 | Question Bank | `POST/GET /api/v1/topics/{topicId}/questions` | Full — matches API-006 |
| 1 | Student Groups | `POST/GET /api/v1/groups`, `GET/PUT /api/v1/groups/{id}`, `POST/DELETE /api/v1/groups/{id}/members[/{memberId}]` | Full — create/edit = Admin/Instructor |
| 2 | Schedule Slots | `POST/GET /api/v1/slots`, `GET /api/v1/slots/{id}` | Full — matches API-001/API-002, no-overlap rule (§5 step 1) |
| 2 | Bookings | `POST /api/v1/slots/{id}/book`, `DELETE /api/v1/bookings/{id}` | Full — matches API-003/API-004, pessimistic-lock capacity enforcement (NFR-002/R-001), late-cancellation window |
| — | Audit Trail | *(internal — `AuditService`, no endpoint yet)* | Recorder built and wired into every Sprint 1–5 mutating service |
| 3 | Artifact Submissions | `POST/GET /api/v1/groups/{groupId}/artifacts`, `GET /api/v1/artifacts/{id}`, `POST /api/v1/artifacts/{id}/accept` | Full — matches API-005 (client supplies `fileUrl`; no file storage in this codebase); resubmitting the same title auto-supersedes the previous version |
| 4 | Meetings & Minutes | `POST /api/v1/bookings/{bookingId}/meetings`, `GET/PUT.../start`/`.../end /api/v1/meetings/{id}`, `POST/GET /api/v1/meetings/{id}/requirements`, `PUT /api/v1/requirements/{id}`, `POST /api/v1/meetings/{id}/minutes/generate`, `PUT .../minutes/sign`, `GET .../minutes` | Full — matches API-007/008/009; minutes generation is a deterministic template, not a real AI call; sign-off is the same endpoint for both Leader (submit) and Instructor (approve/reject) |
| 5 | Evaluation & Reporting | `POST/GET /api/v1/groups/{groupId}/evaluations`, `GET /api/v1/evaluations/{id}`, `GET /api/v1/reports/summary` | Full — matches API-010/API-011; evaluations are created already Published (one-click "Save and Publish" per UC-004); reports summary scoped to Admin (no "Dept Head" role exists) |

RBAC roles: `ADMIN`, `INSTRUCTOR`, `GROUP_LEADER`, `STUDENT`, enforced with
`@PreAuthorize` per blueprint.md §11. All 5 sprints now have full service/controller
layers, each following `BookingService`'s shape (validate → mutate →
`auditService.record(...)` inside the same `@Transactional`).

### Run it in IntelliJ IDEA

1. **Open the project**: `File → Open...` and select this folder (the one with `pom.xml`).
   IntelliJ detects it as a Maven project and downloads dependencies automatically —
   watch the Maven tool window on the right for progress.
2. **Start PostgreSQL**: the easiest path is Docker —
   ```bash
   docker compose up -d
   ```
   This starts Postgres on `localhost:5432` with the database/user/password already
   baked in (see `docker-compose.yml`). No Docker? Install PostgreSQL locally and
   create a database/user matching `.env.example`, or point `DB_URL` at any Postgres
   instance you already have.
3. **Set environment variables** for the run configuration: copy `.env.example` to `.env`
   as a reference, then in IntelliJ go to the auto-generated `CapstoneTrackingBackendApplication`
   run configuration → **Modify options → Environment variables** and paste the values
   (or install the *EnvFile* plugin and point it at `.env` directly).
   The app runs with sane defaults even if you set nothing, **except** you must have
   Postgres reachable at the default URL.
4. **Run**: click the green ▶ next to `CapstoneTrackingBackendApplication.main()`, or
   `mvn spring-boot:run` from a terminal.
5. **Verify**: open http://localhost:8080/swagger-ui.html — you should see all endpoints
   grouped by tag (Auth, Users, Topics, Question Bank, Student Groups, Schedule Slots, Bookings,
   Artifact Submissions, Meetings, Requirement Logs, Meeting Minutes, Evaluations, Reports).

On first boot, Flyway runs, in order:
- `V1__init_schema.sql` — Sprint 1 tables (users, topics, student_groups, group_members, question_bank_items)
- `V2__seed_admin.sql` — one Admin account
- `V3__sprint2_5_schema.sql` — every remaining table in the Data Model (schedule_slots, bookings,
  meeting_sessions, requirement_logs, meeting_minutes, evaluation_records, artifact_submissions,
  system_audit_trail)

Log in as the seeded admin:

```
email:    admin@fpt.edu.vn
password: Admin@123
```

**Change or remove this seed account before any shared/deployed environment.**

### Quick smoke test (curl)

```bash
# 1. Log in as admin
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@fpt.edu.vn","password":"Admin@123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# 2. Create a topic as admin
curl -s -X POST http://localhost:8080/api/v1/topics \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"topicCode":"T001","title":"Smart Attendance System","category":"IoT"}'

# 3. As that same admin (standing in for an Instructor here), publish a slot
curl -s -X POST http://localhost:8080/api/v1/slots \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{"startTime":"2026-10-01T08:00:00Z","endTime":"2026-10-01T08:30:00Z","durationMinutes":30,"capacity":1,"locationType":"ONLINE","meetingUrl":"https://meet.example.com/x"}'
```

Booking itself needs a `GROUP_LEADER` token — see `BookingFlowIntegrationTest` for a
full worked example (create instructor, group, leader, slot, then book/cancel).

### Running tests

```bash
mvn test
```

Tests run against an in-memory H2 database (`application-test.yml`, `spring.profiles.active=test`),
so they don't need Postgres running.

- `AuthFlowIntegrationTest` — register → login → access-protected-endpoint → RBAC-rejection, end to end.
- `BookingFlowIntegrationTest` — the three rules that actually matter for NFR-002/R-001: a slot's
  last seat can't be double-booked (409), a group can't hold two active bookings (400), and a
  booking can't be cancelled inside the 2-hour window (400). This is a correctness test, not a load
  test — NFR-002 also calls for a k6/JMeter run at ≥50 concurrent requests before Sprint 2 ships.

> **A note on how this codebase was produced:** it was generated and hand-reviewed in a
> sandboxed environment without access to Maven Central, so `mvn compile`/`mvn test`
> could not be executed there to double-check it end to end. Run `mvn clean test` as your
> first step after opening the project — if anything doesn't compile, it is most likely a
> small dependency-version mismatch, easy to spot from the error and fix from there.

### Design notes / where the blueprint mapped to code

- **NFR-002 / R-001 (no over-booking under concurrency)**: `ScheduleSlotRepository.findByIdForUpdate`
  takes a `SELECT ... FOR UPDATE` row lock, held for the rest of `BookingService.book()`'s transaction.
  Two requests racing for the same slot's last seat now serialize at that line instead of both reading
  "capacity available" and both writing a Confirmed booking. `StudentGroupService.addMember`'s
  "one leader per group" check follows the same check-then-act-inside-`@Transactional` shape, but
  without a row lock — fine there because a losing request just gets a 409 to retry, not a phantom
  double-booking of a physically limited resource.
- **NFR-006 (auditability)**: `SystemAuditTrail` + `AuditService` are built and wired into
  `BookingService.book()`/`cancel()` (`AuditAction.CREATE`/`CANCEL`). `AuditService.record(...)` requires
  an existing transaction (`Propagation.MANDATORY`) so the audit row and the state change it documents
  always commit or roll back together. Extend the same one-line call into the Sprint 4/5 services
  (`MeetingMinute` approval, `EvaluationRecord` scoring) as they're built.
- **RBAC**: enforced with `@PreAuthorize` at the controller layer rather than in services, so the
  permission model is visible directly on each endpoint. Known simplification carried over from
  Sprint 1: a `GROUP_LEADER` can call booking/member endpoints for *any* group ID, not just their
  own — role checks don't yet verify resource ownership. Add an ownership check (e.g. a custom
  `@PreAuthorize("@groupSecurity.isLeaderOf(#groupId)")`) before this goes further than local dev.
- **Connection pool**: capped deliberately small in `application.yml` (`maximum-pool-size: 15`) —
  NFR-002's 50-concurrent-groups scenario is a burst of short row-locked transactions, not 50 held-open
  connections; a bigger pool just moves the queuing from the pool to Postgres's own lock manager.
  Re-tune once you have a real k6 run to look at.
- **Google SSO (A-005)**: `JwtTokenProvider`/`AuthService` are structured so a Google-issued identity
  can be exchanged for the same JWT this API already issues — that OAuth2 exchange itself isn't
  implemented here; `spring-boot-starter-oauth2-client` is the natural next dependency for it.
- **EvaluationRecord is Restricted Confidential (§11)**: the entity itself has a javadoc reminder never
  to return it directly from a controller — build a DTO for it the way `UserResponse`/`TopicResponse`
  already do, before wiring up Sprint 5's endpoints.
