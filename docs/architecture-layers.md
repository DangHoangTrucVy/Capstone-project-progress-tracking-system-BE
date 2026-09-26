# Các tầng kiến trúc của backend

Bản đồ "5 tầng" (nền tảng → sản phẩm → đưa ra thế giới → chịu tải → đi sâu) áp vào project này: tầng nào đã có,
nằm ở đâu trong code, bật tắt thế nào.

| Tầng | Hạng mục | Trong project | Trạng thái |
|---|---|---|---|
| 1 · Nền tảng | Git & GitHub | Repo nhóm, làm việc qua PR vào `main` | ✅ |
| | Linux & Bash | Docker image Alpine, script export sơ đồ C4 | ✅ |
| | SQL · PostgreSQL | PostgreSQL 16, Flyway `db/migration` V1–V6 | ✅ |
| 2 · Làm ra sản phẩm | Java | Java 17, Spring Boot 3.3 | ✅ |
| | REST API | `/api/v1/**`, Swagger UI | ✅ |
| | React · Next.js | Repo FE riêng | — |
| | GraphQL | Chưa có | ⚪ |
| 3 · Đưa ra thế giới | Docker | `Dockerfile`, `docker-compose.yml` (Postgres, Floci, Redis, app) | ✅ |
| | CI/CD | CI: `.github/workflows/ci.yml` (test với Floci + Redis, build image). CD: Railway tự deploy `main` | ✅ |
| | Cloud · AWS | **S3** lưu tài liệu, **SQS** hàng đợi sự kiện; local/CI chạy trên [Floci](https://github.com/floci-io/floci) | ✅ |
| 4 · Chịu tải thật | Redis | Cache slot/milestone + chống dò mật khẩu đăng nhập | ✅ |
| | Kafka | SQS đóng vai trò hàng đợi sự kiện; Kafka chưa có | ⚪ |
| | Kubernetes | Chưa có | ⚪ |
| | System Design | Khoá hàng khi đặt slot, sự kiện gửi sau commit, xử lý idempotent, cache xoá sau commit | 🟡 |
| 5 · Đi sâu | AI & LLM | Chỗ cắm sẵn: tạo biên bản họp (`MeetingMinuteService.generate`) | ⚪ |

## Kiến trúc tầng 3–4

```
             ┌──────────────────────── Spring Boot app ────────────────────────┐
  FE ──REST──▶ Controller ─▶ Service ──JPA──▶ PostgreSQL                         │
             │                  │  └─ @Cacheable ──▶ Redis (slot, milestone)    │
             │                  │  └─ FileStorage ──▶ S3  (local: thư mục đĩa)  │
             │                  └─ DomainEvent ─(sau commit)─▶ EventSink         │
             │                                        ├─ inprocess ─┐            │
             │                                        └─ SQS ──▶ SqsEventConsumer┤
             │                                                      ▼            │
             │                                         NotificationHandler ──▶ notifications
             └──────────────────────────────────────────────────────────────────┘
```

- **FileStorage** (`storage/`): `LocalFileStorage` hoặc `S3FileStorage`, chọn bằng `STORAGE_TYPE`. Kiểm tra dung lượng,
  đuôi file, tên file dùng chung ở `UploadPolicy`.
- **Sự kiện → thông báo** (`notification/`): service phát `DomainEvent` (nộp tài liệu, đặt/huỷ lịch, báo cáo tiến độ,
  GV nhận xét); `DomainEventRelay` chỉ chuyển đi **sau khi commit**. Với SQS, consumer đọc hàng đợi bất đồng bộ;
  message chỉ bị xoá khi xử lý xong, và `(event_id, recipient_id)` là unique nên nhận trùng không tạo thông báo trùng.
  API: `GET /api/v1/notifications`, `/unread-count`, `PUT /{id}/read`, `PUT /read-all`.
- **Redis** (`config/CacheConfig`, `auth/*LoginAttemptLimiter`): cache DTO (không cache entity), TTL 10 phút,
  xoá sau commit; sai mật khẩu 5 lần trong 15 phút → 429 `TOO_MANY_LOGIN_ATTEMPTS`.

## Bật / tắt từng phần

Mặc định không cần gì ngoài PostgreSQL (đúng như Railway đang chạy). `docker-compose.yml` bật hết.

| Biến môi trường | Mặc định | Giá trị để bật |
|---|---|---|
| `STORAGE_TYPE` | `local` | `s3` (+ `S3_BUCKET`, `S3_CREATE_BUCKET=true` khi dùng Floci) |
| `MESSAGING_TYPE` | `inprocess` | `sqs` (+ `SQS_QUEUE_NAME`, `SQS_CREATE_QUEUE=true` khi dùng Floci) |
| `AWS_ENDPOINT_URL` | trống (AWS thật) | `http://localhost:4566` (Floci) |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | `ap-southeast-1`, chuỗi AWS mặc định | Floci nhận giá trị bất kỳ, VD `test` |
| `REDIS_ENABLED` + `CACHE_TYPE` | `false` + `none` | `true` + `redis` (+ `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`) |

## Chạy local

```bash
docker compose up -d --build          # Postgres + Floci + Redis + app (S3, SQS, Redis đều bật)
# Console Floci: http://localhost:4566/_floci/ui
```

Chạy app từ IDE nhưng dùng hạ tầng trong Docker: `docker compose up -d postgres floci redis`, rồi đặt các biến ở
bảng trên (`AWS_ENDPOINT_URL=http://localhost:4566`, `REDIS_HOST=localhost`, ...).

## Test

- `mvn test`: toàn bộ test, không cần hạ tầng (4 test hạ tầng tự bỏ qua).
- `INFRA_TESTS=true mvn test` với Floci + Redis đang chạy: thêm `InfrastructureIntegrationTest` (S3, SQS, cache Redis,
  rate limit Redis). CI luôn chạy chế độ này.

## Đưa lên Railway (khi cần)

Railway hiện chạy mặc định (volume + in-process + không Redis). Để bật:
- **Redis:** thêm dịch vụ Redis trong project, đặt `REDIS_ENABLED=true`, `CACHE_TYPE=redis`,
  `REDIS_HOST/PORT/PASSWORD` lấy từ biến của dịch vụ Redis.
- **S3/SQS:** cần bucket và queue AWS thật (Floci chỉ để dev/CI): đặt `STORAGE_TYPE=s3`, `MESSAGING_TYPE=sqs`,
  `S3_BUCKET`, `SQS_QUEUE_NAME` và khoá IAM; **không** đặt `AWS_ENDPOINT_URL`. File cũ trên volume không tự chuyển sang S3.
