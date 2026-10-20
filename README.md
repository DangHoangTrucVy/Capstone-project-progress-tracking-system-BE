# Website ghép nhóm
*(Team Formation Website)*

Website giúp sinh viên ghép nhóm **Capstone Project (đồ án tốt nghiệp)** trong từng học kỳ, chỉ trong cùng ngành.
Moderator điều phối mọi nhóm, kể cả nhóm khóa, cho tới khi chốt danh sách và xuất Excel bàn giao.
Chỉ đăng nhập bằng Google với email trong danh sách đã nhập; không hỗ trợ mật khẩu.

---

## 📌 Tài liệu

- [**Đối chiếu yêu cầu v2.9 và các sửa đổi**](docs/requirements-v2.9-audit.md).
- [**Yêu cầu v2.9 được cung cấp ngày 20/10/2026**](docs/requirements/team-formation-v2.9.txt).
- [Thiết kế v2.8 để tham khảo lịch sử](docs/design/team-formation-v2.8.md); các quy tắc v2.9 được ưu tiên.
- 🗄️ [**ERD**](docs/erd.png) ([Mermaid](docs/erd.mmd), [SVG](docs/erd.svg)).
- 🏗️ [**Các tầng kiến trúc**](docs/architecture-layers.md): Docker, CI/CD, SQS (Floci), Redis — bật/tắt và cách chạy.

> Các tài liệu `blueprint.md`, `intent.md`, `docs/requirements/*`, `docs/sequence/*`, `docs/c4/*`,
> `docs/feature-list-and-architecture.md`, `docs/workflow-implementation.md` mô tả phạm vi cũ (Capstone Project Progress
> Tracking) và sẽ được vẽ lại sau khi nhóm duyệt requirement.

---

## 🎯 Chức năng

1. **Học kỳ và ngành:** Admin tạo học kỳ (SP26, SU26, FA26); hệ thống tự tạo cấu hình Capstone, mặc định kỳ học 8.
   Moderator được gán từ danh sách giảng viên đã nhập, cấu hình kỳ học trước khi mở ghép nhóm và thời hạn chờ đơn.
   Mỗi ngành có một *phạm vi ghép nhóm* với giai đoạn và deadline riêng.
2. **Nhập danh sách:** danh sách sinh viên (MSSV, họ tên, email, SĐT, kỳ, ngành), file sinh viên không đủ điều kiện và
   danh sách giảng viên; luôn có bước kiểm tra trước khi ghi, nhập lại không tạo trùng.
3. **Ghép nhóm:** tạo nhóm (1–5 người), Apply / Invite, Leader duyệt, khóa nhóm, rời nhóm, chuyển Leader, tìm kiếm theo
   kỹ năng, Like ứng viên, hồ sơ có chế độ riêng tư.
4. **Deadline và điều phối:** tới deadline hệ thống hủy đơn chờ, giải tán nhóm dưới 3 người; Moderator xếp sinh viên lẻ,
   thêm, đưa ra, chuyển, gộp, giải tán, lập nhóm, chỉ định Leader, xử lý khiếu nại, gia hạn.
   Moderator có quyền điều phối cả trước deadline, kể cả nhóm Leader khóa; sau chốt phải mở lại trước.
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
`V19__requirements_v29.sql` enforces the v2.9 rules and records lecturer import provenance;
the dev profile also loads `V2001__v29_dev_configuration.sql` after the existing sample seed.

Tài khoản mẫu chỉ để tham khảo dữ liệu. Để đăng nhập, cấu hình `GOOGLE_CLIENT_IDS` và nhập email Google thực tế
vào danh sách sinh viên/giảng viên; tài khoản Admin được quản lý riêng. Mọi môi trường đều chỉ dùng Google.

| Email | Vai trò |
|---|---|
| `admin@fpt.edu.vn` | Admin |
| `lecturer01@fpt.edu.vn` | Moderator of FA26 (dev seed) |
| `se180001@fpt.edu.vn` | Student, Leader of Team Alpha in FA26 / CAP / SE (dev seed) |

Sau khi nâng cấp từ bản cũ, Admin cần nhập lại danh sách giảng viên để xác nhận nguồn tài khoản trước khi
giảng viên đăng nhập hoặc được gán Moderator. Dữ liệu dev được đánh dấu riêng trong seed V2001.

### Quick smoke test (curl)

```bash
# Obtain a Google ID token for an imported email, then look at FA26
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/google \
  -H "Content-Type: application/json" \
  -d '{"idToken":"<GOOGLE_ID_TOKEN>"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

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
