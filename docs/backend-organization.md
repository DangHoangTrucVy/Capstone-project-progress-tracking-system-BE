# Backend được tổ chức như thế nào

Sơ đồ cấu trúc nội bộ của backend (`com.capstone.tracking`), vẽ từ cây thư mục `src/main/java` thực tế.
Phần luồng chạy, API và sự kiện nằm ở [`backend-architecture-and-apis.md`](backend-architecture-and-apis.md).

## 1. Kiến trúc phân tầng

Một monolith Spring Boot, chia **theo feature** (package-by-feature), bên trong mỗi feature chia **theo tầng**.

```mermaid
flowchart TB
    FE([Client / FE]) -->|"REST + Bearer JWT"| P

    subgraph P["Presentation"]
        CT["*Controller<br/>@RestController · @PreAuthorize<br/>nhận/trả dto/*Request · *Response"]
    end

    subgraph B["Business"]
        SV["*Service<br/>@Transactional · quy tắc nghiệp vụ<br/>phát DomainEvent · ghi audit"]
    end

    subgraph D["Data access"]
        RP["*Repository<br/>Spring Data JPA<br/>(khoá hàng: lockById, findByIdForUpdate)"]
        EN["Entity<br/>extends BaseEntity"]
    end

    subgraph X["Cross-cutting"]
        SEC["security + config<br/>JWT filter · SecurityConfig · CORS"]
        EXC["common.exception<br/>GlobalExceptionHandler"]
        AUD["audit"]
        ST["storage<br/>FileStorage"]
        NOTI["notification<br/>outbox · SSE · email"]
    end

    DB[("PostgreSQL<br/>Flyway migrations")]

    SEC -.-> CT
    CT --> SV --> RP --> EN --> DB
    EXC -.-> CT
    SV -.-> AUD
    SV -.-> ST
    SV -.->|"DomainEvent"| NOTI
```

Quy ước: `Controller` không chứa nghiệp vụ; `Service` giữ transaction và quy tắc; `Repository` chỉ truy vấn;
entity không bao giờ trả thẳng ra ngoài mà đi qua DTO.

## 2. Cấu trúc bên trong một module

Mọi feature đều theo cùng một khuôn (ví dụ `scheduling/`):

```mermaid
flowchart LR
    subgraph M["scheduling/"]
        direction TB
        C1["ScheduleSlotController<br/>BookingController"]
        S1["ScheduleSlotService<br/>BookingService"]
        R1["ScheduleSlotRepository<br/>BookingRepository"]
        E1["ScheduleSlot · Booking<br/>(entity)"]
        EN1["SlotStatus · BookingStatus<br/>LocationType (enum)"]
        DT["dto/<br/>SlotCreateRequest · BookRequest<br/>SlotResponse · BookingResponse"]
        C1 --> S1 --> R1 --> E1
        E1 --- EN1
        C1 -.-> DT
        S1 -.-> DT
    end
```

| Thành phần | Vai trò |
|---|---|
| `XxxController` | Route `/api/v1/...`, kiểm quyền, validate DTO |
| `XxxService` | Nghiệp vụ, transaction, event, audit |
| `XxxRepository` | Spring Data JPA |
| `Xxx` (entity) + `XxxStatus` (enum) | Mô hình dữ liệu và trạng thái |
| `dto/` | Request/Response, tách khỏi entity |

## 3. Bản đồ package

```mermaid
flowchart LR
    ROOT["com.capstone.tracking<br/>CapstoneTrackingBackendApplication"]

    ROOT --> INF["Hạ tầng dùng chung"]
    ROOT --> ID["Danh tính & thiết lập"]
    ROOT --> GR["Nhóm"]
    ROOT --> WF["Workflow đồ án"]
    ROOT --> OUT["Đầu ra"]

    INF --> i1["config (4)<br/>Security · Cache · OpenApi · JpaAuditing"]
    INF --> i2["security (4)<br/>JWT filter · provider · UserDetails"]
    INF --> i3["common (8)<br/>BaseEntity · VnTime · exception/*"]
    INF --> i4["audit (4)"]
    INF --> i5["storage (6)<br/>Local | S3"]
    INF --> i6["aws (2)<br/>AwsConfig · AwsProperties"]

    ID --> d1["auth (13)<br/>+ google/ · LoginAttemptLimiter"]
    ID --> d2["user (11)<br/>User · Role · Campus · Profile"]
    ID --> d3["eligibility (2)"]
    ID --> d4["semester (4)"]
    ID --> d5["milestone (6)"]
    ID --> d6["topic (8)"]
    ID --> d7["question (7)"]

    GR --> g1["group (40)<br/>StudentGroup · join · invite · vote<br/>leave · roster · roster-change"]

    WF --> w1["proposal (15)"]
    WF --> w2["scheduling (20)"]
    WF --> w3["meeting (26)<br/>session · requirement · minutes"]
    WF --> w4["progress (12)"]
    WF --> w5["artifact (9)"]
    WF --> w6["evaluation (7)"]
    WF --> w7["review (12)"]
    WF --> w8["defense (10)"]
    WF --> w9["warning (8)"]

    OUT --> o1["notification (31)<br/>outbox · SQS · SSE · email/"]
    OUT --> o2["overview (3)"]
    OUT --> o3["report (3)"]
```

Số trong ngoặc là số file `.java` của package.

## 4. Tài nguyên và test ngoài `src/main/java`

```mermaid
flowchart LR
    subgraph R["src/main/resources"]
        y1["application.yml<br/>application-dev.yml"]
        m1["db/migration<br/>V1–V12 (schema)"]
        s1["db/seed<br/>V1000–V1003 (dev data)"]
    end
    subgraph T["src/test/java"]
        t1["Integration test theo feature<br/>(MockMvc, H2/Postgres)"]
        t2["Infrastructure / Postgres test<br/>chạy khi bật cờ"]
        t3["WorkflowTestSupport"]
    end
    subgraph O["Gốc repo"]
        o1["Dockerfile · docker-compose(.prod).yml"]
        o2[".github/workflows/ci.yml"]
        o3["docs/ · README.md · blueprint.md"]
    end
```
