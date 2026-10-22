# Website ghép nhóm
*(Team Formation Website)*

Website giúp sinh viên ghép nhóm **Capstone Project (đồ án tốt nghiệp)** trong từng học kỳ, chỉ trong cùng ngành.
Moderator điều phối mọi nhóm trong học kỳ được gán, kể cả nhóm khóa và sau chốt danh sách.
Sinh viên dùng Google theo danh sách nhập; Admin/Moderator dùng email và mật khẩu.

---

## 📌 Tài liệu

- [**Báo cáo hiện hành: đối chiếu, API mới và phần chưa hoàn thành (21/10/2026)**](docs/requirements-review-20261021.md).
- [Yêu cầu hiện hành được gửi lại ngày 21/10/2026](docs/requirements/team-formation-v2.9-revised-20261021.txt).
- [Đối chiếu cũ theo tài liệu nhận ngày 20/10](docs/requirements-v2.9-audit.md) — chỉ tham khảo lịch sử.
- [**Yêu cầu v2.9 được cung cấp ngày 20/10/2026**](docs/requirements/team-formation-v2.9.txt).
- [Thiết kế v2.8 để tham khảo lịch sử](docs/design/team-formation-v2.8.md); các quy tắc v2.9 được ưu tiên.
- 🗄️ [**ERD**](docs/erd.png) ([Mermaid](docs/erd.mmd), [SVG](docs/erd.svg)).
- 🏗️ [**Các tầng kiến trúc**](docs/architecture-layers.md): Docker, CI/CD, SQS (Floci), Redis — bật/tắt và cách chạy.

> Các tài liệu `blueprint.md`, `intent.md`, các bản requirement trước 21/10, `docs/sequence/*`, `docs/c4/*`,
> `docs/feature-list-and-architecture.md`, `docs/workflow-implementation.md` mô tả phạm vi cũ (Capstone Project Progress
> Tracking) và sẽ được vẽ lại sau khi nhóm duyệt requirement.

---

## 🎯 Chức năng

1. **Học kỳ và ngành:** Admin tạo học kỳ (SP26, SU26, FA26); hệ thống tự tạo cấu hình Capstone, mặc định kỳ học 8.
   Admin tạo tài khoản Moderator rồi phân công học kỳ; Moderator thiết lập timeline và thời hạn chờ đơn.
   Kỳ nhập cố định là 8, chuẩn bị cho Capstone kỳ 9; không có đổi ngành.
   Mỗi ngành có một *phạm vi ghép nhóm* với giai đoạn và deadline riêng.
2. **Nhập danh sách:** danh sách sinh viên (MSSV, họ tên, email, kỳ 8, ngành; SĐT tùy chọn), điều kiện tham gia
   và tỷ lệ tín chỉ nếu có; luôn có bước kiểm tra trước khi ghi, nhập lại không tạo trùng.
3. **Ghép nhóm:** tạo nhóm (1–5 người), Apply / Invite, Leader duyệt, khóa nhóm, rời nhóm, chuyển Leader, tìm kiếm theo
   kỹ năng, Like ứng viên, hồ sơ có chế độ riêng tư.
4. **Deadline và điều phối:** tới deadline hệ thống hủy đơn chờ, giải tán nhóm dưới 3 người; Moderator xếp sinh viên lẻ,
   thêm, đưa ra, chuyển, gộp, giải tán, lập nhóm, chỉ định Leader, xử lý khiếu nại, gia hạn.
   Moderator có quyền điều phối cả trước deadline, kể cả nhóm Leader khóa; sau chốt vẫn được điều phối và cập nhật điều kiện, không cần mở lại.
5. **Chốt trạng thái nhóm, thống kê, xuất Excel bàn giao.** Lịch sử thao tác để làm sau.
6. **Thông báo** trên web (chuông, thời gian thực) và qua email.

---

## 👥 Nhóm phát triển

- **Nhóm:** Group 2
- **Môn học:** SWD / Capstone Project

---

## ⚙️ Backend Service (Spring Boot)

### Stack

- Java 17, Spring Boot 3.3.4, Maven
- Spring Web, Spring Data JPA, Spring Security (stateless JWT, Google sign-in)
- PostgreSQL + Flyway migrations
- springdoc-openapi (Swagger UI)
- Apache POI (đọc file nhập, xuất Excel)
- JUnit 5 + MockMvc + H2, embedded PostgreSQL 16 cho các test `*PostgresTest`
- AWS SDK v2 (SQS) — [Floci](https://github.com/floci-io/floci) locally and in CI; Redis (login rate limit, notification fan-out); GitHub Actions CI

### Vai trò

| Vai trò | Là gì |
|---|---|
| `ADMIN` | Quản trị: học kỳ, nhập danh sách, tài khoản, gán Moderator; làm được mọi việc của Moderator |
| `LECTURER` | **Moderator**, tài khoản do Admin tạo; chỉ có quyền trong học kỳ được gán |
| `STUDENT` | Sinh viên có tên trong danh sách của ít nhất một học kỳ; **Leader** là cờ của một thành viên trong nhóm |

Quyền theo học kỳ và theo nhóm được kiểm tra ở server (`Access`, `GroupCore.requireLeader`, `StudentGate`).

### Run it in IntelliJ IDEA

1. **Open the project**: `File → Open...` and select this folder (the one with `pom.xml`).
2. **Start a separate development PostgreSQL**: `docker compose -f docker-compose.infra.yml up -d postgres redis floci`
   starts the optional infrastructure project, with Postgres on `localhost:5432` (see `docker-compose.infra.yml`).
   The normal Docker Desktop stack below uses an internal database and serves the backend on port 8081.
3. **Set environment variables**: copy `.env.example` to `.env` as a reference and paste the values into the
   `CapstoneTrackingBackendApplication` run configuration. Defaults work as long as Postgres is reachable.
4. **Run** `CapstoneTrackingBackendApplication.main()` (or `mvn spring-boot:run`). With `SPRING_PROFILES_ACTIVE=dev`
   the dev seed (`db/seed/V2000`) adds sample data.
5. **Verify**: open http://localhost:8080/swagger-ui.html.

Flyway applies `V1`–`V17` (history) then `V18__team_formation_schema.sql`, which drops the former capstone-tracking
tables, keeps the user accounts and creates the team formation schema.
`V19` giữ thay đổi của tài liệu cũ; `V20__revised_v29_group_finalization.sql` chuyển cờ chốt sang nhóm,
bỏ sử dụng trạng thái học kỳ/nguồn giảng viên và xóa mật khẩu sinh viên cũ. Seed `V2002` cũng xóa mật khẩu
sinh viên mẫu sau seed lịch sử; các migration đã tồn tại không bị chỉnh sửa.

Trong dữ liệu dev, Admin và Moderator đăng nhập bằng mật khẩu `Admin@123`. Sinh viên chỉ dùng Google:
cấu hình `GOOGLE_CLIENT_IDS` và nhập/sửa email Google thực tế trong danh sách sinh viên.
Admin được tạo sẵn trong DB; không tạo thêm Admin qua API.

| Email | Vai trò |
|---|---|
| `admin@fpt.edu.vn` | Admin |
| `lecturer01@fpt.edu.vn` | Moderator of FA26 (dev seed) |
| `se180001@fpt.edu.vn` | Student, Leader of Team Alpha in FA26 / CAP / SE (dev seed) |

Không nhập danh sách giảng viên. Admin tạo Moderator qua `POST /api/v1/users/moderators`;
`PUT /api/v1/users/{id}/password` đặt/đổi mật khẩu Moderator, gồm tài khoản giảng viên cũ chưa có mật khẩu.

### Quick smoke test (curl)

```bash
# Sign in as the dev Admin and look at FA26
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@fpt.edu.vn","password":"Admin@123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

curl -s http://localhost:8080/api/v1/semesters -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/v1/semesters/FA26/scopes -H "Authorization: Bearer $TOKEN"
curl -s -o list.xlsx "http://localhost:8080/api/v1/semesters/FA26/export" -H "Authorization: Bearer $TOKEN"
```

### Running tests

```bash
mvn test
```

Most tests run on in-memory H2. `PostgresMigrationTest` and the `*PostgresTest` classes start a real PostgreSQL 16
in-process (no Docker), apply every migration + the dev seed, validate the entities and re-run the flows on it;
`ConcurrentJoinPostgresTest` checks that simultaneous joins never exceed 5 members or put a student in two groups.
`InfrastructureIntegrationTest` needs Floci and Redis (`INFRA_TESTS=true`).

> On JDK 21+ newer than Mockito's Byte Buddy supports, add `-DargLine=-Dnet.bytebuddy.experimental=true`.

## API cleanup (21/10/2026)

Client integration and removed operations: [API cleanup notes](docs/api-cleanup-20261021.md).
Use semester-scoped student/group lists and `/users/moderators`; legacy compatibility operations have been removed.

## Start the current Docker backend

Run `start-backend.cmd` or `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-backend.ps1`.
It starts `capstone-project-progress-tracking-system-be` on port 8081 using `.env.lan`, waits for healthy services,
and recreates missing containers while reusing the database volumes. Add `-Build` to rebuild Java changes.

Docker Desktop's project name now matches the root `docker-compose.yml` and the current containers:
`capstone-project-progress-tracking-system-be`. Its Start button can start the same services as the script.
The root Compose includes `docker-compose.desktop.yml` and automatically reads `.env.lan`.
The existing `capstone-debug_pgdata` and `capstone-debug_uploads` volumes are reused as external volumes;
the old `capstone-debug` containers are stopped and retained for recovery. Never run both database containers
against the same data volume at once. On this installation the backend stays on port 8081, so FE and tunnels
keep their connection. See [Docker Desktop repair notes](docs/docker-desktop-fix-20261022.md).
Compose `start` only starts existing containers; the startup script uses `up` to recover missing ones.
`start-all.ps1` also starts this backend before starting the frontend.

## Demo request timing

Apply/Invite lifetime is configured in **minutes** with `PUT /api/v1/subjects/{subjectId}`:
`{"requestTtlMinutes":15}`. Values from 1 to 20160 minutes are accepted. Existing requests retain their saved
expiry when this setting changes; new requests use the new value. FE: **Deadline và chốt → Thời hạn Apply / Invite**.

Local `.env.lan` uses `FORMATION_REQUEST_TTL_MINUTES=15` for new Capstone configurations and
`FORMATION_MIN_DEADLINE_NOTICE_MINUTES=10` to allow short demo deadlines. The main backend also defaults to 15 and 10 minutes when the variables are omitted. Changing the request lifetime does not change the formation deadline;
the Moderator selects that deadline separately. Migration V21 preserves historical hour settings as minutes; V22 sets the database default for new configurations to 15 minutes without rewriting existing data.
