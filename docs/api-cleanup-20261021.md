# Dọn API cũ — 21/10/2026

Phạm vi: cả FE và BE, theo tài liệu `requirements/team-formation-v2.9-revised-20261021.txt` và xác nhận của người dùng.
Đây là thay đổi phá vỡ tương thích với client cũ. Các endpoint được bỏ khỏi controller, không chỉ ẩn Swagger.

## Backend

| HTTP operation đã bỏ | Cách dùng hiện hành |
|---|---|
| `POST /api/v1/users` | `POST /api/v1/users/moderators`; sinh viên phải import, Admin có sẵn trong DB |
| `GET /api/v1/subjects/{subjectId}/students` | `GET /api/v1/semesters/{semester}/students` |
| `GET /api/v1/subjects/{subjectId}/groups` | `GET /api/v1/semesters/{semester}/groups`, response phân trang `content` |
| `POST /api/v1/semesters/{semester}/subjects` | Tạo học kỳ tự khởi tạo Capstone; không tạo môn thủ công |
| `DELETE /api/v1/subjects/{subjectId}` | Capstone bắt buộc của học kỳ; không cung cấp thao tác xóa riêng |

Đã xóa `LecturerImportService` không còn người gọi và DTO/dependency lịch sử nhập không còn endpoint.
Các API đăng ký, campus, duyệt đăng ký, lịch hẹn, tài liệu, báo cáo cũ, nhập giảng viên và audit
không thuộc backend hiện hành. Không khôi phục chúng để chiều theo frontend cũ.

Giữ API subject/scopes cho Capstone, cấu hình thời hạn yêu cầu, sinh viên tìm nhóm/tạo nhóm,
Apply/Invite, chuyển Leader, khiếu nại và điều phối vì tài liệu mới vẫn cần các nghiệp vụ đó.
Không xóa API chỉ vì giao diện chưa dùng hết. Không sửa migration đã áp dụng hoặc xóa dữ liệu DB.

## Frontend

Repository FE cùng cấp: `Capstone-project-progress-tracking-system-FE`.

- Xóa 29 file thuộc service/màn hình cũ, gồm đăng ký và duyệt đăng ký, lịch hẹn, tài liệu,
  báo cáo/eligibility cũ và các dashboard phụ thuộc chúng. Bản sao trước sửa nằm trong
  `target/fe-api-cleanup-backup` của BE; thư mục này không đưa vào Git.
- Thay bằng `formationService.js`: mỗi operation khai báo rõ HTTP method và URL hiện hành;
  kiểm tra tham số đường dẫn, phân trang đầy đủ, multipart và tải Excel.
- Dashboard ghép nhóm dùng dữ liệu theo học kỳ: nhóm, sinh viên, chưa nhóm/không đủ điều kiện,
  import preview/confirm, eligibility, điều phối, deadline/gia hạn/chốt/mở lại, thống kê/Excel,
  tạo/phân công Moderator. Không còn cấu hình giả chỉ lưu localStorage.
- Dashboard sinh viên dùng `/me/semesters`, trạng thái nhóm/Leader từ membership, các API
  requests/leader-transfers hiện hành và hồ sơ riêng tư. Quyền và điều kiện nghiệp vụ vẫn do server kiểm tra.
- Login chỉ dùng mật khẩu cho Admin/Moderator và Google theo roster cho sinh viên; bỏ campus và đăng ký/duyệt.
  `/register` chuyển về login. Role `LECTURER` mở dashboard Moderator; không còn tạo role Leader riêng.
- Nginx tiếp tục proxy cùng origin `/api/v1` sang backend mới trên host port 8081.

## Kiểm tra

- Backend Java 17 trong Docker: 60 tests, 57 pass, 3 skip, không failure/error.
  Lệnh chạy chọn `*Test,!*Postgres*`; lần này không chạy lại bộ PostgreSQL nhúng.
  Có kiểm tra các operation cũ vắng khỏi OpenAPI và trả 404/405 khi gọi trực tiếp.
- Frontend: 5 tests pass, đối chiếu mọi operation của API client với OpenAPI backend đang chạy;
  quét source để chặn đường dẫn của luồng cũ, kiểm tra phân trang, upload và Excel.
- ESLint các file FE thay đổi không lỗi/cảnh báo; production build FE thành công.
- Đã build/restart backend 8081 và FE public. Swagger hiện có 76 URL paths; một URL có thể có nhiều HTTP methods.
- Đăng nhập qua tên miền FE với Admin và Moderator FA26 đều thành công; đọc được 21 sinh viên, 3 nhóm từ PostgreSQL thật.

## Giới hạn cần biết

Không thêm đăng nhập giả lập, không tạo Gmail thật hoặc cấp lại mật khẩu sinh viên.
Sinh viên mẫu chỉ đăng nhập được sau khi email roster khớp tài khoản Google cá nhân của người test.
Google thật/SMTP và toàn bộ thao tác ghi qua trình duyệt chưa được kiểm chứng từ đầu đến cuối trong lần dọn này.
Giao diện hiện tập trung vào nghiệp vụ ghép nhóm; một số kết quả kiểm tra/chi tiết còn hiển thị JSON để phục vụ test.
Các API quản lý học kỳ/ngành và một số thao tác nâng cao vẫn có trên Swagger, chưa có đủ màn hình thao tác tương ứng trong FE.
