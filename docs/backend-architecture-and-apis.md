# Backend Architecture & APIs — Diagrams

Sơ đồ kiến trúc và API của backend, vẽ từ **code hiện tại** (controller, `SecurityConfig`, `notification/`, `storage/`,
`application.yml`) chứ không phải từ `blueprint.md`. Dùng Mermaid nên xem trực tiếp trên GitHub / IntelliJ / VS Code.
Sơ đồ C4 cũ ở `docs/c4/` chưa có Redis, S3, SQS, email và các module workflow (proposal, review, defense...), nên file này
là bản cập nhật.

Mục lục: [1. Tổng quan](#1-tổng-quan-hệ-thống) · [2. Package](#2-package--module-map) · [3. Request pipeline & bảo mật](#3-request-pipeline--bảo-mật) ·
[4. API theo module](#4-api-map-theo-module) · [5. Phân quyền](#5-ma-trận-phân-quyền-rbac) · [6. Luồng đặt lịch](#6-luồng-đặt-slot-chống-over-booking) ·
[7. Sự kiện & thông báo](#7-sự-kiện--thông-báo) · [8. Vòng đời nghiệp vụ](#8-vòng-đời-nghiệp-vụ) · [9. Triển khai](#9-triển-khai)

---

## 1. Tổng quan hệ thống

```mermaid
flowchart LR
    subgraph Users["Người dùng (FE riêng repo)"]
        S[Student]
        GL[Group Leader]
        I[Instructor]
        C[Council]
        A[Admin]
    end

    FE["Web App (SPA)<br/>CORS: localhost:3000 / 5173"]
    G["Google Sign-In<br/>tokeninfo API"]

    subgraph BE["Backend API — Spring Boot 3.3 / Java 17"]
        API["REST /api/v1/**<br/>Swagger UI /swagger-ui.html"]
        SCH["Scheduled jobs<br/>DomainEventDispatcher<br/>EmailOutboxDispatcher"]
    end

    DB[("PostgreSQL 16<br/>Flyway V1–V12")]
    REDIS[("Redis<br/>cache + rate limit")]
    S3[("S3 / ổ đĩa local<br/>tài liệu nộp")]
    SQS[["SQS<br/>domain events"]]
    SMTP["SMTP<br/>(Gmail)"]

    S & GL & I & C & A --> FE
    FE -- "JSON/HTTPS<br/>Bearer JWT" --> API
    API -- "verify id_token" --> G
    API -- "JPA / JDBC" --> DB
    API -. "@Cacheable<br/>REDIS_ENABLED" .-> REDIS
    API -. "FileStorage<br/>STORAGE_TYPE" .-> S3
    SCH -. "EventSink<br/>MESSAGING_TYPE" .-> SQS
    SQS -. "SqsEventConsumer" .-> API
    SCH -. "MAIL_ENABLED" .-> SMTP
    SCH --> DB
```

Nét đứt = thành phần **tuỳ chọn**, bật bằng biến môi trường. Mặc định chỉ cần PostgreSQL
(`STORAGE_TYPE=local`, `MESSAGING_TYPE=inprocess`, `CACHE_TYPE=none`, `MAIL_ENABLED=false`).

---

## 2. Package / module map

Package-by-feature dưới `com.capstone.tracking`; mỗi module gồm `Controller → Service → Repository → Entity (+ dto/)`.

```mermaid
flowchart TB
    subgraph Cross["Cross-cutting"]
        direction LR
        security["security<br/>JwtAuthenticationFilter<br/>JwtTokenProvider"]
        config["config<br/>SecurityConfig · CacheConfig<br/>OpenApiConfig · JpaAuditing"]
        common["common<br/>BaseEntity · VnTime<br/>GlobalExceptionHandler"]
        audit["audit<br/>AuditService → system_audit_trail"]
        storage["storage<br/>FileStorage: Local | S3<br/>UploadPolicy"]
    end

    subgraph Identity["Danh tính"]
        auth["auth<br/>login · register · Google<br/>LoginAttemptLimiter"]
        user["user<br/>users · profile"]
        elig["eligibility<br/>import danh sách đủ điều kiện"]
    end

    subgraph Setup["Thiết lập học kỳ"]
        semester["semester<br/>lịch học kỳ"]
        milestone["milestone"]
        topic["topic"]
        question["question<br/>question bank"]
    end

    subgraph Group["Hình thành nhóm"]
        grp["group<br/>StudentGroup · join/invite/vote<br/>leave · roster · roster-change"]
    end

    subgraph Flow["Workflow đồ án"]
        proposal["proposal<br/>đợt + đề xuất đề tài"]
        scheduling["scheduling<br/>slot · booking · ScheduleGuard<br/>(guard dùng cho Defense)"]
        meeting["meeting<br/>session · requirement · minutes"]
        progress["progress<br/>báo cáo tuần"]
        artifact["artifact<br/>tài liệu nộp"]
        evaluation["evaluation"]
        review["review<br/>Review 1 / 2 / 3"]
        defense["defense<br/>bảo vệ"]
        warning["warning<br/>cờ cảnh báo"]
    end

    subgraph Out["Đầu ra"]
        overview["overview<br/>tổng quan nhóm"]
        report["report<br/>thống kê (Admin)"]
        notification["notification<br/>outbox · SSE · email"]
    end

    auth --> user
    grp --> user & topic & elig & semester
    proposal --> grp & topic
    scheduling --> grp & user
    meeting --> scheduling
    artifact --> grp & storage
    progress --> grp
    evaluation --> grp
    review --> grp & proposal
    defense --> review & grp
    warning --> grp
    overview --> grp & progress & artifact & review & defense & warning
    report --> scheduling & meeting & grp

    scheduling & artifact & progress & proposal & review & defense & grp & warning -. "DomainEvent" .-> notification
    scheduling & grp & artifact & review & defense -. "ghi vết" .-> audit
```

Mũi tên liền = phụ thuộc nghiệp vụ chính (không liệt kê hết); nét đứt = phát sự kiện / ghi audit.

---

## 3. Request pipeline & bảo mật

### 3.1 Đường đi của một request

```mermaid
flowchart LR
    REQ([HTTP request]) --> CORS[CORS]
    CORS --> JWT["JwtAuthenticationFilter<br/>đọc Bearer token"]
    JWT -->|"token hợp lệ"| CTX[SecurityContext<br/>ROLE_*]
    JWT -->|"thiếu / sai"| EP["AuthEntryPoint → 401"]
    CTX --> AUTHZ{"SecurityConfig<br/>URL rule"}
    AUTHZ -->|"PUBLIC"| CTRL
    AUTHZ -->|"đúng role"| CTRL["Controller<br/>@PreAuthorize"]
    AUTHZ -->|"sai role"| F403[403]
    CTRL --> DTO["Bean Validation<br/>(dto/*Request)"]
    DTO --> SVC["Service<br/>@Transactional"]
    SVC --> REPO[Repository / JPA]
    REPO --> DB[(PostgreSQL)]
    SVC -. "lỗi nghiệp vụ" .-> EX["GlobalExceptionHandler<br/>ApiException → ErrorResponse"]
    SVC --> RESP([JSON response])
```

- **Stateless**: không session, CSRF tắt, mọi request mang `Authorization: Bearer <JWT>`.
- **Public**: `/api/v1/auth/{register,login,google,campuses}`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`.
- **Mọi role đã đăng nhập**: `/auth/me`, `/eligibility/me`, `/me/**`, `/notifications/**`, nhóm tham gia/lời mời/rời nhóm.
- Còn lại: kiểm tra theo `@PreAuthorize` trên từng endpoint (xem [mục 5](#5-ma-trận-phân-quyền-rbac)).

### 3.2 Đăng nhập (mật khẩu hoặc Google)

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant FE
    participant AC as AuthController
    participant AS as AuthService
    participant L as LoginAttemptLimiter<br/>(Redis | in-memory)
    participant GV as GoogleTokenVerifier
    participant DB as PostgreSQL

    alt POST /auth/login
        FE->>AC: email + password
        AC->>AS: login()
        AS->>L: đã sai ≥ 5 lần / 15 phút?
        L-->>AS: chặn → 429 TOO_MANY_LOGIN_ATTEMPTS
        AS->>DB: tìm user, so BCrypt
        AS->>L: ghi nhận lần sai / reset khi đúng
    else POST /auth/google
        FE->>AC: Google id_token
        AC->>AS: loginWithGoogle()
        AS->>GV: verify (tokeninfo)
        GV-->>AS: GoogleIdentity (email)
        AS->>DB: tìm user theo email (miền cho phép: fpt.edu.vn)
    end
    AS-->>FE: LoginResponse { accessToken, role, ... }
    Note over FE,AC: Các request sau gửi Bearer token;<br/>GET /auth/me trả về profile hiện tại
```

---

## 4. API map theo module

Tất cả bắt đầu bằng `/api/v1`. Swagger UI liệt kê đầy đủ request/response. Viết tắt role: **AD**=Admin, **IN**=Instructor,
**CO**=Council, **GL**=Group Leader, **ST**=Student, **\***=mọi role đã đăng nhập.

```mermaid
flowchart LR
    API(["/api/v1"])
    API --> auth["/auth<br/>5 endpoints"]
    API --> me["/me · /users<br/>/eligibility<br/>10 endpoints"]
    API --> setup["/semesters · /milestones<br/>/topics · /topics/id/questions<br/>13 endpoints"]
    API --> grp["/groups · /applications<br/>/invites · /leave-requests<br/>/roster-change-reports<br/>~36 endpoints"]
    API --> wf["/proposal-rounds · /topic-proposals<br/>/reviews · /defenses<br/>24 endpoints"]
    API --> sch["/slots · /bookings<br/>/meetings · /requirements<br/>16 endpoints"]
    API --> trk["/groups/id/progress · /artifacts<br/>/evaluations · /warning-flags<br/>~28 endpoints"]
    API --> out["/notifications · /reports<br/>/groups/id/overview<br/>7 endpoints"]
```

### 4.1 Auth & Identity

| Method | Path | Role | Mô tả |
|---|---|---|---|
| POST | `/auth/register` | public | Đăng ký (giới hạn miền email) |
| POST | `/auth/login` | public | Đăng nhập, trả JWT |
| POST | `/auth/google` | public | Đăng nhập bằng Google id_token |
| GET | `/auth/campuses` | public | Danh sách campus |
| GET | `/auth/me` | \* | Người dùng hiện tại |
| POST / GET | `/users` | AD | Tạo / liệt kê user |
| GET / PUT | `/users/{id}` | AD | Xem / sửa user |
| GET / PUT | `/me/profile` | \* | Hồ sơ cá nhân |
| POST | `/eligibility/import` | AD | Import danh sách đủ điều kiện |
| PUT | `/eligibility/{userId}` | AD | Sửa trạng thái đủ điều kiện |
| GET | `/eligibility/ineligible` | AD | Danh sách không đủ điều kiện |
| GET | `/eligibility/me` | \* | Tình trạng của tôi |

### 4.2 Thiết lập học kỳ

| Method | Path | Role | Mô tả |
|---|---|---|---|
| PUT / GET | `/semesters/{semester}` | AD / \* | Lịch học kỳ |
| GET / PUT | `/semesters/{semester}/join-settings` | AD | Cấu hình tham gia nhóm |
| POST / GET | `/milestones`, GET `/milestones/{id}` | AD / \* | Mốc tiến độ (cache Redis) |
| POST / GET | `/topics`, GET/PUT `/topics/{id}` | AD (ghi) / \* (đọc) | Đề tài |
| POST / GET | `/topics/{topicId}/questions` | AD, GL / \* | Question bank |

### 4.3 Hình thành nhóm (`group`)

| Method | Path | Role | Mô tả |
|---|---|---|---|
| POST / GET | `/groups` | AD, ST, GL / \* | Tạo / liệt kê nhóm |
| GET / PUT | `/groups/{id}` | \* / AD | Chi tiết / cập nhật |
| POST | `/groups/{id}/members` | AD | Thêm thành viên (tối đa 5 active) |
| DELETE | `/groups/{id}/members/{memberId}` | AD, GL | Xoá thành viên |
| PUT | `/groups/{id}/leader` | AD | Đổi trưởng nhóm |
| POST | `/groups/{id}/lock` · `/unlock` | AD | Khoá / mở nhóm |
| POST | `/groups/{id}/roster/submit` | GL | Gửi danh sách cho GVHD |
| POST | `/groups/{id}/roster/review` | IN, AD | Duyệt / từ chối danh sách |
| POST | `/groups/{gid}/applications` | ST | Xin vào nhóm |
| GET | `/groups/{gid}/applications` · `/me/applications` | GL / ST | Xem đơn |
| POST | `/applications/{id}/approve` · `/reject` · `/withdraw` | GL / ST | Xử lý đơn |
| POST / GET | `/applications/{id}/votes` | thành viên | Bỏ phiếu cho đơn |
| POST / GET | `/groups/{gid}/invites` | GL | Mời / xem lời mời |
| POST | `/invites/{id}/accept` · `/decline` · `/revoke` | ST / GL | Phản hồi lời mời |
| GET | `/me/invites` | ST | Lời mời của tôi |
| POST / GET | `/groups/{gid}/leave-requests` | thành viên | Xin rời nhóm |
| POST | `/leave-requests/{id}/approve` · `/reject` · `/withdraw` | GL / ST | Xử lý đơn rời |
| POST / GET | `/groups/{gid}/roster-change-reports` | IN (tạo), IN+AD (xem) | Báo cáo thay đổi nhân sự |
| GET | `/roster-change-reports` | AD | Tất cả báo cáo |
| POST | `/roster-change-reports/{id}/resolve` | AD | Giải quyết |

### 4.4 Đề tài, Review, Bảo vệ

| Method | Path | Role | Mô tả |
|---|---|---|---|
| POST / GET | `/proposal-rounds` | AD / \* | Mở / xem đợt đề xuất |
| PUT | `/proposal-rounds/{id}/close` | AD | Đóng đợt |
| POST / GET | `/groups/{gid}/topic-proposals` | GL / \* | Nộp / xem đề xuất của nhóm |
| GET | `/topic-proposals`, `/topic-proposals/{id}` | IN, CO, AD / \* | Danh sách / chi tiết |
| POST | `/topic-proposals/{id}/forward` | IN, AD | GVHD chọn 1 đề tài chuyển Hội đồng |
| POST | `/topic-proposals/{id}/decision` | CO, AD | Hội đồng duyệt / từ chối |
| POST | `/reviews` · `/reviews/clone` | AD | Lên lịch / nhân bản lịch Review |
| GET | `/reviews`, `/reviews/mine`, `/reviews/{id}`, `/groups/{gid}/reviews` | theo role | Xem Review |
| POST | `/reviews/{id}/result` · `/revision-complete` | IN, CO, AD | Kết quả / hoàn tất chỉnh sửa |
| POST | `/defenses` · `/defenses/rolling` | AD | Lên lịch bảo vệ (đơn / cuốn chiếu) |
| GET | `/defenses`, `/defenses/mine`, `/defenses/{id}`, `/groups/{gid}/defenses` | theo role | Xem buổi bảo vệ |
| POST | `/defenses/{id}/result` | IN, CO, AD | Ghi kết quả đậu / rớt |

### 4.5 Lịch hẹn & họp

| Method | Path | Role | Mô tả |
|---|---|---|---|
| POST | `/slots` | IN, AD | Mở slot |
| GET | `/slots`, `/slots/{id}` | \* | Tìm / xem slot (cache) |
| POST | `/slots/{id}/book` | GL | Đặt slot (khoá hàng) |
| DELETE | `/bookings/{id}` | GL, IN, AD | Huỷ lịch |
| POST | `/bookings/{bookingId}/meetings` | GL, IN | Tạo phiên họp |
| GET | `/meetings/{id}` | \* | Xem phiên họp |
| PUT | `/meetings/{id}/start` · `/end` | GL, IN | Bắt đầu / kết thúc |
| POST / GET | `/meetings/{id}/requirements` | GL, IN / \* | Ghi yêu cầu |
| PUT | `/requirements/{id}` | GL, IN | Cập nhật yêu cầu |
| POST | `/meetings/{id}/minutes/generate` | GL, IN | Sinh biên bản (theo template) |
| PUT | `/meetings/{id}/minutes/sign` | GL, IN, AD | Ký biên bản 2 bên |
| GET | `/meetings/{id}/minutes` | \* | Xem biên bản |

### 4.6 Theo dõi tiến độ

| Method | Path | Role | Mô tả |
|---|---|---|---|
| POST / GET | `/groups/{gid}/progress` | GL / \* | Nộp / xem báo cáo tuần |
| GET | `/groups/{gid}/progress/summary` | \* | Tóm tắt tiến độ |
| GET / PUT | `/progress/{id}` | \* / GL | Chi tiết / sửa |
| PUT | `/progress/{id}/feedback` | IN, AD | Nhận xét |
| POST / GET | `/groups/{gid}/artifacts` (alias `/documents`) | GL / \* | Nộp (multipart) / liệt kê tài liệu |
| GET | `/artifacts/{id}`, `/artifacts/{id}/file` | \* | Metadata / tải file |
| POST | `/artifacts/{id}/accept` | IN, AD | Chấp nhận |
| PUT | `/artifacts/{id}/feedback` | IN, AD | Nhận xét |
| POST / GET | `/groups/{gid}/evaluations` | IN / \* | Chấm điểm 3 tiêu chí |
| GET | `/evaluations/{id}` | \* | Chi tiết |
| POST / GET | `/groups/{gid}/warning-flags` | IN, AD / \* | Cờ cảnh báo |
| PUT | `/warning-flags/{id}/resolve` | IN, AD | Gỡ cờ |

### 4.7 Đầu ra

| Method | Path | Role | Mô tả |
|---|---|---|---|
| GET | `/groups/{gid}/overview` | \* | Tổng quan nhóm |
| GET | `/reports/summary` | AD | Thống kê toàn học kỳ |
| GET | `/notifications` · `/unread-count` | \* | Danh sách / số chưa đọc |
| GET | `/notifications/stream` | \* | SSE realtime |
| PUT | `/notifications/{id}/read` · `/read-all` | \* | Đánh dấu đã đọc |

---

## 5. Ma trận phân quyền (RBAC)

```mermaid
flowchart LR
    subgraph Roles
        AD[ADMIN]
        IN[INSTRUCTOR]
        CO[COUNCIL]
        GL[GROUP_LEADER]
        ST[STUDENT]
    end
    subgraph Capabilities
        c1["Quản lý user · topic · milestone<br/>semester · eligibility · report"]
        c2["Lên lịch Review / Defense<br/>mở đợt proposal · khoá nhóm"]
        c3["Mở slot · chấm điểm<br/>nhận xét · cờ cảnh báo"]
        c4["Forward / duyệt đề tài<br/>chấm Review · Defense"]
        c5["Đặt slot · nộp tài liệu & tiến độ<br/>nộp proposal · gửi roster"]
        c6["Tạo nhóm · xin vào / rời nhóm<br/>xem dữ liệu nhóm"]
    end
    AD --> c1 & c2 & c3 & c4
    IN --> c3 & c4
    CO --> c4
    GL --> c5 & c6
    ST --> c6
```

| Nhóm quyền | AD | IN | CO | GL | ST |
|---|:-:|:-:|:-:|:-:|:-:|
| User / Topic / Milestone / Semester / Eligibility / Report | ✅ | | | | |
| Mở đợt proposal, lên lịch Review/Defense, khoá nhóm | ✅ | | | | |
| Mở slot, nhận xét tiến độ/tài liệu, chấm điểm, cờ cảnh báo | ✅ | ✅ | | | |
| Forward đề tài, duyệt roster | ✅ | ✅ | | | |
| Duyệt đề tài, ghi kết quả Review/Defense | ✅ | ✅ | ✅ | | |
| Đặt slot, nộp tài liệu/tiến độ/proposal, gửi roster | | | | ✅ | |
| Tạo nhóm, xin vào/rời nhóm, xem dữ liệu nhóm | ✅ | ✅ | ✅ | ✅ | ✅ |

> Tóm tắt từ `@PreAuthorize` trong các controller; chi tiết từng endpoint ở mục 4.

---

## 6. Luồng đặt slot (chống over-booking)

Hai Group Leader cùng đặt slot cuối. `BookingService.book()` khoá hàng nhóm (`studentGroupService.lockById`) rồi khoá hàng
slot (`findByIdForUpdate`) nên các lần đặt cùng slot được **tuần tự hoá**; người đến sau thấy slot đầy và nhận 409.
(`ScheduleGuard`, khoá lịch toàn cục, hiện chỉ do `DefenseService` dùng khi xếp lịch bảo vệ.)

```mermaid
sequenceDiagram
    autonumber
    actor L1 as Leader A
    actor L2 as Leader B
    participant BC as BookingController
    participant BS as BookingService
    participant DB as PostgreSQL
    participant EV as DomainEvent
    participant R as Redis cache

    L1->>BC: POST /slots/{id}/book
    L2->>BC: POST /slots/{id}/book
    BC->>BS: book() — @PreAuthorize GROUP_LEADER
    BS->>DB: khoá hàng nhóm, kiểm tra là leader active
    BS->>DB: nhóm đã có booking CONFIRMED? → 400
    BS->>DB: SELECT slot ... FOR UPDATE
    Note over DB: Request B chờ tại đây
    BS->>DB: slot còn chỗ? nhóm trùng lịch trong ngày?
    BS->>DB: INSERT booking (CONFIRMED) + audit row
    BS->>EV: publish BOOKING_CONFIRMED
    BS-->>L1: 200 { bookingId, CONFIRMED }
    DB-->>BS: commit → nhả khoá
    BS->>R: @CacheEvict slot + slot_search
    Note over BS,DB: Request B vào, thấy slot đã đầy
    BS-->>L2: 409 Conflict
```

---

## 7. Sự kiện & thông báo

Service chỉ phát `DomainEvent`. Sự kiện được **lưu vào outbox cùng transaction** rồi mới gửi đi sau commit; lỗi thì
`DomainEventDispatcher` retry với exponential backoff (tối đa 1 giờ).

```mermaid
flowchart TB
    SVC["Service<br/>(@Transactional)"] -- "publishEvent(DomainEvent)" --> REL["DomainEventRelay"]
    REL -- "BEFORE_COMMIT<br/>ghi domain_event_outbox" --> OB[("domain_event_outbox")]
    REL -- "AFTER_COMMIT<br/>deliver(eventId)" --> DIS["DomainEventDispatcher<br/>khoá hàng · attempts++ · backoff"]
    JOB(["@Scheduled mỗi 5s<br/>retryPending()"]) --> DIS
    DIS --> SINK{"EventSink<br/>MESSAGING_TYPE"}
    SINK -- "inprocess" --> NH
    SINK -- "sqs" --> SQS[["SQS queue"]] --> CONS["SqsEventConsumer<br/>xoá message khi xử lý xong"] --> NH
    NH["NotificationHandler"] --> NT[("notifications<br/>unique(event_id, recipient_id)")]
    NH --> BR{"NotificationBroadcaster"}
    BR -- "local" --> SSE["NotificationStreamRegistry<br/>SSE /notifications/stream"]
    BR -- "redis pub/sub" --> SSE
    NH -- "sự kiện có emailed=true" --> EO[("email_outbox")]
    EJOB(["@Scheduled<br/>EmailOutboxDispatcher<br/>SKIP LOCKED + retry"]) --> EO
    EJOB --> SMTP["EmailSender<br/>SMTP | Logging"]
```

- **Idempotent**: nhận trùng message không tạo thông báo trùng nhờ unique `(event_id, recipient_id)`.
- **Email**: chỉ các event có cờ `emailed` (vd `TOPIC_APPROVED`, `REVIEW_RESULT`, `DEFENSE_SCHEDULED`, `DOCUMENT_FEEDBACK`...);
  To = leader, CC = thành viên + GVHD. `MAIL_ENABLED=false` thì chỉ ghi log.
- 29 loại event trong `DomainEventType`.

---

## 8. Vòng đời nghiệp vụ

### 8.1 Pipeline đồ án

```mermaid
flowchart LR
    E["Eligibility<br/>import"] --> F["Hình thành nhóm<br/>apply / invite / vote"]
    F --> R["Roster<br/>DRAFT→SUBMITTED→APPROVED"]
    R --> P["Topic proposal<br/>PENDING_INSTRUCTOR→PENDING_COUNCIL→APPROVED"]
    P --> M["Giai đoạn thực hiện<br/>slot · meeting · minutes<br/>progress · artifact · evaluation"]
    M --> V1["Review 1<br/>tuần 3-4"]
    V1 --> V2["Review 2<br/>tuần 7"]
    V2 --> V3["Review 3 — hội đồng kín<br/>tuần 14"]
    V3 --> D["Defense (tối đa 2 lần)<br/>SCHEDULED→PASSED / FAILED"]
    D --> END(["Group: COMPLETED<br/>hoặc FAILED (rớt lần 2)"])
    W["Warning flags<br/>(bất kỳ lúc nào)"] -.-> M
```

### 8.2 Trạng thái các thực thể chính

```mermaid
stateDiagram-v2
    direction LR
    state "StudentGroup" as G {
        [*] --> FORMED
        FORMED --> ACTIVE: đề tài được duyệt
        ACTIVE --> COMPLETED: Defense đạt
        ACTIVE --> FAILED: rớt Defense lần 2
        COMPLETED --> ARCHIVED
        FAILED --> ARCHIVED
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Roster" as RS {
        [*] --> DRAFT
        DRAFT --> SUBMITTED: leader gửi
        SUBMITTED --> APPROVED: GVHD duyệt
        SUBMITTED --> REJECTED: GVHD từ chối
        REJECTED --> SUBMITTED: gửi lại
        SUBMITTED --> DRAFT: đổi nhân sự
        APPROVED --> DRAFT: đổi nhân sự
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "TopicProposal" as TP {
        [*] --> PENDING_INSTRUCTOR: leader nộp
        PENDING_INSTRUCTOR --> PENDING_COUNCIL: GVHD forward 1 đề tài
        PENDING_COUNCIL --> APPROVED: Hội đồng duyệt
        PENDING_COUNCIL --> REJECTED: Hội đồng từ chối
    }
```

```mermaid
stateDiagram-v2
    direction LR
    state "Defense" as DF {
        [*] --> SCHEDULED: Admin lên lịch (attempt 1 / 2)
        SCHEDULED --> PASSED: ghi kết quả → group COMPLETED
        SCHEDULED --> FAILED: ghi kết quả
        FAILED --> [*]: attempt 1 → bảo vệ lần 2<br/>attempt 2 → group FAILED
    }
```

---

## 9. Triển khai

```mermaid
flowchart TB
    subgraph Dev["Local / CI — docker-compose.yml"]
        direction LR
        app1["app<br/>eclipse-temurin:17-jre-alpine"]
        pg1[("postgres:16-alpine")]
        redis1[("redis")]
        floci["Floci<br/>giả lập S3 + SQS :4566"]
        app1 --> pg1 & redis1 & floci
    end

    subgraph Prod["Production-like — docker-compose.prod.yml (self-host)"]
        direction LR
        CFT["Cloudflare Tunnel<br/>không mở cổng"] --> app2["app container"]
        app2 --> pg2[("postgres:16-alpine<br/>volume pgdata<br/>chỉ app truy cập được")]
    end

    subgraph Rw["Railway (theo docs/architecture-layers.md)"]
        direction LR
        app3["app — auto-deploy từ main"] --> pg3[("PostgreSQL")]
        app3 --> vol[("Volume<br/>LocalFileStorage")]
        app3 -. "tuỳ chọn" .-> aws["Redis · AWS S3 + SQS thật"]
    end

    GH["GitHub PR → main"] --> CI["GitHub Actions ci.yml<br/>mvn test (INFRA_TESTS=true)<br/>build Docker image"]
    CI -->|"merge main"| Rw
```

| Thành phần | Công nghệ |
|---|---|
| Runtime | Java 17, Spring Boot 3.3 (Web, Security, Data JPA, Validation, Scheduling, Cache) |
| DB & migration | PostgreSQL 16, Flyway (`db/migration` V1–V12; seed dev ở `db/seed`), `ddl-auto=validate` |
| Auth | JWT (jjwt), BCrypt, Google tokeninfo, rate-limit đăng nhập (Redis / in-memory) |
| Lưu trữ file | `FileStorage` → `LocalFileStorage` hoặc `S3FileStorage` (`UploadPolicy`: dung lượng, đuôi file) |
| Bất đồng bộ | Outbox + `EventSink` (in-process hoặc SQS), SSE, email outbox |
| Tài liệu API | springdoc OpenAPI — `/swagger-ui.html`, `/v3/api-docs` |
| Test | JUnit + MockMvc integration test; test hạ tầng (Floci, Redis) và Postgres chạy khi bật cờ |

Chi tiết bật/tắt từng phần: xem [`architecture-layers.md`](architecture-layers.md). ERD: [`erd.mmd`](erd.mmd).
