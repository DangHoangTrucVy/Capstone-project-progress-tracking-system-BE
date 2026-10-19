# Website ghép nhóm
*(Team Formation Website)*

Website giúp sinh viên tự ghép nhóm theo môn học trong từng học kỳ, chỉ trong cùng ngành, và giúp Moderator điều phối,
chốt danh sách và xuất file Excel bàn giao. Phiên bản đầu tiên làm môn **Capstone Project (đồ án tốt nghiệp)**; mô hình
"môn" cho phép thêm môn khác sau mà không phải làm lại.

---

## 📌 Tài liệu

- 📐 [**Thiết kế backend theo requirement v2.8**](docs/design/team-formation-v2.8.md): phân tích yêu cầu, mô hình nghiệp vụ,
  giai đoạn của phạm vi ghép nhóm, danh sách API, database và các câu hỏi còn chờ.
- 🗄️ [**ERD**](docs/erd.png) ([Mermaid](docs/erd.mmd), [SVG](docs/erd.svg)).
- 🏗️ [**Các tầng kiến trúc**](docs/architecture-layers.md): Docker, CI/CD, SQS (Floci), Redis — bật/tắt và cách chạy.

> Các tài liệu `blueprint.md`, `intent.md`, `docs/requirements/*`, `docs/sequence/*`, `docs/c4/*`,
> `docs/feature-list-and-architecture.md`, `docs/workflow-implementation.md` mô tả phạm vi cũ (Capstone Project Progress
> Tracking) và sẽ được vẽ lại sau khi nhóm duyệt requirement.

---

## 🎯 Chức năng

1. **Học kỳ, môn, ngành:** Admin tạo học kỳ (SP26, SU26, FA26) và gán một Moderator; Moderator tạo môn cần ghép nhóm.
   Mỗi ngành trong một môn là một *phạm vi ghép nhóm* có giai đoạn và deadline riêng.
2. **Nhập danh sách:** danh sách sinh viên (MSSV, họ tên, email, SĐT, kỳ, ngành), file sinh viên không đủ điều kiện và
   danh sách giảng viên; luôn có bước kiểm tra trước khi ghi, nhập lại không tạo trùng.
3. **Ghép nhóm:** tạo nhóm (1–5 người), Apply / Invite, Leader duyệt, khóa nhóm, rời nhóm, chuyển Leader, tìm kiếm theo
   kỹ năng, Like ứng viên, hồ sơ có chế độ riêng tư.
4. **Deadline và điều phối:** tới deadline hệ thống hủy đơn chờ, giải tán nhóm dưới 3 người; Moderator xếp sinh viên lẻ,
   chuyển, lập nhóm, xử lý khiếu nại, gia hạn.
5. **Chốt danh sách, thống kê, xuất Excel bàn giao, lịch sử thao tác.**
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
| `LECTURER` | Giảng viên có trong danh sách đã nhập; là **Moderator** của học kỳ được Admin gán |
| `STUDENT` | Sinh viên có tên trong danh sách của ít nhất một học kỳ; **Leader** là cờ của một thành viên trong nhóm |

Quyền theo học kỳ và theo nhóm được kiểm tra ở server (`Access`, `GroupCore.requireLeader`, `StudentGate`).

### Run it in IntelliJ IDEA

1. **Open the project**: `File → Open...` and select this folder (the one with `pom.xml`).
2. **Start PostgreSQL**: `docker compose up -d` starts Postgres on `localhost:5432` with the database/user/password
   already baked in (see `docker-compose.yml`). No Docker? Point `DB_URL` at any Postgres you have.
3. **Set environment variables**: copy `.env.example` to `.env` as a reference and paste the values into the
   `CapstoneTrackingBackendApplication` run configuration. Defaults work as long as Postgres is reachable.
4. **Run** `CapstoneTrackingBackendApplication.main()` (or `mvn spring-boot:run`). With `SPRING_PROFILES_ACTIVE=dev`
   the dev seed (`db/seed/V2000`) adds sample data.
5. **Verify**: open http://localhost:8080/swagger-ui.html.

Flyway applies `V1`–`V17` (history) then `V18__team_formation_schema.sql`, which drops the former capstone-tracking
tables, keeps the user accounts and creates the team formation schema.

Accounts (password `Admin@123`, needs `PASSWORD_LOGIN_ENABLED=true`):

| Email | Vai trò |
|---|---|
| `admin@fpt.edu.vn` | Admin |
| `lecturer01@fpt.edu.vn` | Moderator of FA26 (dev seed) |
| `se180001@fpt.edu.vn` | Student, Leader of Team Alpha in FA26 / CAP / SE (dev seed) |

**Change or remove these accounts before any shared/deployed environment.**

### Quick smoke test (curl)

```bash
# Log in as the dev Moderator and look at FA26
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"lecturer01@fpt.edu.vn","password":"Admin@123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

curl -s http://localhost:8080/api/v1/semesters -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/v1/semesters/FA26/subjects -H "Authorization: Bearer $TOKEN"
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
