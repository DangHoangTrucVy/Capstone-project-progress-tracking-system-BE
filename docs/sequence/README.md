# Sơ đồ tuần tự (sequence diagram) cho toàn bộ API

13 sơ đồ trong 9 file (ảnh SVG trong thư mục [`svg/`](svg/)), phủ đủ **141 operation** trên Swagger (`/v3/api-docs`). Mỗi lời gọi API trong sơ đồ ghi đúng `METHOD /api/v1/...` như trên Swagger.

## Sơ đồ mẫu được tạo thế nào

Hình mẫu được vẽ bằng **Mermaid `sequenceDiagram`**. Các dấu hiệu nhận ra:

| Trong hình | Cú pháp Mermaid |
|---|---|
| Hình người ở đầu và cuối cột (Academic Affairs, Student...) | `actor ST as Student` (Mermaid tự lặp tên ở cuối cột) |
| Hộp chữ nhật (Web Client, Backend, Database, Excel Engine) | `participant BE as Core System / Backend` |
| Vòng tròn đen đánh số 1, 2, 3... trên mỗi mũi tên | `autonumber` |
| Mũi tên liền (gửi yêu cầu) | `FE->>BE: importStudents(termId, rosterFile)` |
| Mũi tên nét đứt (trả kết quả) | `BE-->>FE: 200 OK` |
| Nền màu cho từng giai đoạn | `rect rgb(235, 243, 255)` ... `end` |
| Thanh vàng tiêu đề giai đoạn | `Note over A,DB: 1. INITIAL SETUP ...` |
| Khung `alt` với các nhánh `[...]` | `alt Not in roster` ... `else Eligible` ... `end` |
| Khung `opt` (chỉ xảy ra khi có điều kiện) | `opt If removed member is Leader` ... `end` |

Cấu trúc chung: **Actor → Web Client → Backend → Database / hệ thống ngoài**, chia theo giai đoạn nghiệp vụ, mỗi giai đoạn một màu nền.

### Khác biệt giữa hình mẫu và code hiện tại

Sơ đồ ở đây vẽ theo code thật, nên khác hình mẫu ở 5 điểm:

1. **Vai trò:** code có 3 vai trò cho lập nhóm (Student, Leader, Admin). Không có "Academic Affairs" hay "Moderator"; Admin tự import danh sách.
2. **Bước 8-11 (đăng nhập):** gần giống hình mẫu nhưng mã lỗi khác. Email trường chưa có tài khoản (chưa import, chưa tạo) nhận `403 ACCOUNT_NOT_PROVISIONED`. Sinh viên bị đánh dấu không đủ điều kiện vẫn đăng nhập được; BE không cấp "phiên chỉ xem" riêng. Thay vào đó, mọi thao tác lập nhóm của họ trả `403 NOT_ELIGIBLE`.
3. **Bước 19-22 (duyệt Apply):** Leader duyệt thì BE **gửi Invite** (YC10). Sinh viên chỉ vào nhóm khi Accept Invite, lúc đó BE mới huỷ các yêu cầu đang chờ khác.
4. **Giai đoạn 5 (hạn chót):** BE không có job tự giải tán nhóm dưới 3 người hay tự huỷ yêu cầu lúc hết hạn. Hạn chót chỉ đóng cửa sổ (409 cho người không phải Admin). Apply/Invite tự hết hạn sau `ttlHours`.
5. **Excel:** tên sheet thật là `Danh_sach_nhom` và `Chua_co_nhom`. Tên file là `Danh_sach_ghep_nhom_<semester>_<yyyyMMdd_HHmmss>.xlsx`.

## Danh sách file

| File | Swagger tag | Số API | Ảnh SVG |
|---|---|---|---|
| [01-auth-users.md](01-auth-users.md) | Auth, Registrations, Users | 15 | [01-auth-users-1](svg/01-auth-users-1.svg), [01-auth-users-2](svg/01-auth-users-2.svg) |
| [02-semester-eligibility.md](02-semester-eligibility.md) | Semesters, Eligibility, thời hạn lập nhóm | 13 | [02-semester-eligibility](svg/02-semester-eligibility.svg) |
| [03-student-groups.md](03-student-groups.md) | Student Groups | 14 | [03-student-groups](svg/03-student-groups.svg) |
| [04-group-formation.md](04-group-formation.md) | Group Formation (Apply, Invite, rời nhóm, báo thay đổi) | 24 | [04-group-formation-1](svg/04-group-formation-1.svg), [04-group-formation-2](svg/04-group-formation-2.svg) |
| [05-topics-proposals.md](05-topics-proposals.md) | Topics, Question Bank, Topic Proposals | 15 | [05-topics-proposals-1](svg/05-topics-proposals-1.svg), [05-topics-proposals-2](svg/05-topics-proposals-2.svg) |
| [06-scheduling-meetings.md](06-scheduling-meetings.md) | Schedule Slots, Bookings, Meetings, Meeting Minutes, Requirement Logs | 15 | [06-scheduling-meetings](svg/06-scheduling-meetings.svg) |
| [07-progress-documents.md](07-progress-documents.md) | Milestones, Weekly Progress, Documents, Warning Flags, Evaluations | 21 | [07-progress-documents](svg/07-progress-documents.svg) |
| [08-reviews-defenses.md](08-reviews-defenses.md) | Reviews, Defenses | 15 | [08-reviews-defenses](svg/08-reviews-defenses.svg) |
| [09-notifications-reports.md](09-notifications-reports.md) | Notifications, Group Overview, Reports | 9 | [09-notifications-reports-1](svg/09-notifications-reports-1.svg), [09-notifications-reports-2](svg/09-notifications-reports-2.svg) |
| **Tổng** | 25 tag | **141** | 13 ảnh |

Các file 05-08 dùng vai trò Instructor và Council. Hai vai trò này nằm ngoài phạm vi lập nhóm, nhưng API của chúng vẫn có trên Swagger.

## Quy ước

- Lỗi chung không vẽ lại ở từng API: thiếu hoặc sai token trả `401`, sai vai trò (`@PreAuthorize`) trả `403`, body sai trả `400 VALIDATION_FAILED`. Body lỗi luôn là `ErrorResponse`.
- `Outbox event X` nghĩa là BE lưu sự kiện trong cùng transaction. Job chạy mỗi 5 giây biến sự kiện thành thông báo và đẩy qua SSE (xem 9.1).
- Đường dẫn có alias (`/documents` thay cho `/artifacts`) bị ẩn trên Swagger nên không vẽ.

## Cách xem và sửa

- **Ảnh SVG:** mở file trong `svg/` bằng trình duyệt, hoặc chèn vào Word/báo cáo. Ảnh được xuất bằng `mmdc` (mermaid-cli 10.9.1) từ các khối Mermaid; sửa file `.md` rồi xuất lại để cập nhật ảnh.

- **GitHub / VS Code:** mở file `.md`; khối ```` ```mermaid ```` tự hiện thành hình (VS Code cần extension "Markdown Preview Mermaid Support").
- **mermaid.live:** dán nội dung một khối vào https://mermaid.live để xem và xuất PNG/SVG.
- **draw.io:** Arrange → Insert → Advanced → Mermaid, dán nội dung một khối.
