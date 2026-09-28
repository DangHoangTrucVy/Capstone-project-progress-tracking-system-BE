# Quy trình đồ án: hợp đồng backend

Quy trình này áp dụng cho backend hiện tại. Frontend gọi các API dưới đây để triển khai các màn hình tương ứng. Quy mô 40 nhóm là kịch bản kiểm thử, không phải giới hạn cứng số nhóm trong database.

## 1. Cấp tài khoản và đăng nhập

- Admin tạo tài khoản qua `POST /api/v1/users`: `email`, `fullName`, `role`, `campus`; không bắt buộc password khi dùng Google.
- Thành viên nhóm được lưu với role `STUDENT` để quản lý danh sách và nhận CC email. Student không được đăng nhập hoặc sử dụng API bằng token cũ.
- Admin tạo nhóm qua `POST /api/v1/groups`, sau đó thêm thành viên qua `POST /api/v1/groups/{id}/members`. Gửi `{ "userId": "...", "isLeader": true }` để chỉ định Leader; tài khoản được chuyển thành `GROUP_LEADER`.
- Một sinh viên chỉ thuộc một nhóm đang có membership ACTIVE; một nhóm tối đa 5 thành viên và một Leader. Admin quản lý danh sách nhóm, thành viên và việc chỉ định Leader.
- `POST /api/v1/auth/register` trả 403 `REGISTRATION_DISABLED`. Luồng Student tự tạo hoặc tự tham gia nhóm đã đóng.
- `GET /api/v1/auth/campuses` cung cấp lựa chọn Campus. `POST /api/v1/auth/google` nhận `{ "idToken": "...", "campus": "HO_CHI_MINH" }`.
- Email phải thuộc `ALLOWED_EMAIL_DOMAIN` (mặc định `fpt.edu.vn`) và token phải có Google Workspace hosted domain hợp lệ. Tài khoản chưa được Admin tạo trả 403 `ACCOUNT_NOT_PROVISIONED`; Student trả `LEADER_LOGIN_REQUIRED`.
- Campus được cố định ở lần đăng nhập đầu nếu Admin chưa gán; chọn sai Campus trả `CAMPUS_MISMATCH`.
- Frontend lấy `user.role` trong phản hồi login để chuyển Dashboard. Role lấy từ database, không lấy từ thông tin role do client gửi.
- Đăng nhập mật khẩu mặc định tắt. Có thể đặt `PASSWORD_LOGIN_ENABLED=true` để bootstrap Admin/local development; quyền Student vẫn bị chặn. Tắt lại sau khi cấu hình Google.

## 2. Đề xuất và duyệt đề tài

1. Leader nộp `POST /api/v1/groups/{id}/topic-proposals` với `topics` gồm đúng 10 mục ở vòng đầu.
2. Giảng viên hướng dẫn chọn một mục bằng `POST /api/v1/topic-proposals/{id}/forward` với `itemId`.
3. Council/Admin gửi quyết định qua `POST /api/v1/topic-proposals/{id}/decision`, `decision` là `APPROVED` hoặc `REJECTED`; từ chối phải có `feedback`.
4. Deadline thẩm định tính từ lúc chuyển lên hội đồng: vòng 1 là 14 ngày, vòng 2–4 là 10 ngày. Không được APPROVED khi đã tới deadline.
5. Quá hạn không tự động đánh trượt: Hội đồng đóng vòng bằng REJECTED kèm feedback để nhóm có thể nộp lại. Admin mở vòng tiếp theo bằng `POST /api/v1/proposal-rounds`; cửa sổ nộp lại tối đa 10 ngày, mặc định 10 ngày. Đóng sớm bằng `PUT /api/v1/proposal-rounds/{id}/close`.
6. Tối đa 4 vòng. Khóa database bảo vệ thao tác nộp và quyết định trước các request đồng thời.

## 3. Lịch tư vấn

- Giảng viên tạo slot qua `POST /api/v1/slots`, mỗi slot phục vụ một nhóm.
- Leader của chính nhóm đặt bằng `POST /api/v1/slots/{id}/book`, body `{ "groupId": "..." }`.
- Đặt trước ít nhất 24 giờ; tối đa một slot/ngày theo giờ Việt Nam; booking CONFIRMED cũ phải được kết thúc thành ATTENDED hoặc hủy.
- Khóa nhóm trước khi kiểm tra booking đang tồn tại, khóa slot trước khi tăng bộ đếm. Hai request của cùng nhóm tới hai slot khác nhau cũng không vượt qua quy tắc.
- Hủy bằng `DELETE /api/v1/bookings/{id}`, body `{ "reason": "..." }`. Chỉ Leader đúng nhóm, giảng viên sở hữu slot hoặc Admin được hủy; không hủy trong 2 giờ trước buổi họp.
- Câu hỏi trước buổi họp: `POST /api/v1/topics/{topicId}/questions`; Leader chỉ gửi cho đề tài của nhóm mình.

## 4. Tài liệu, tiến độ và cảnh báo

- Chỉ Leader của nhóm được nộp tài liệu và ghi báo cáo tuần. Thành viên thường vẫn có thể được phân công task và nhận CC email.
- `POST /api/v1/groups/{id}/documents`: JSON cho link, multipart cho file. Alias `/artifacts` vẫn được hỗ trợ. Nộp lại cùng tiêu đề tạo version mới và supersede version cũ.
- Feedback bài nộp: `PUT /api/v1/documents/{id}/feedback` hoặc `/api/v1/artifacts/{id}/feedback`:

```json
{ "feedback": "Bổ sung biểu đồ triển khai", "accepted": false }
```

- Chỉ supervisor của nhóm hoặc Admin được nhận xét/duyệt. `accepted=true` chuyển sang ACCEPTED; `false` giữ SUBMITTED để Leader sửa và nộp bản mới. Response có `feedback`, `reviewedById`, `reviewedAt`. Bản đã supersede không được nhận xét lại.
- `/accept` vẫn được hỗ trợ và phát thông báo duyệt bài.
- Milestone progress trên Overview tự tính theo tỷ lệ milestone đã có tài liệu nộp. Đây là tiến độ nộp bài, không phải tỷ lệ bài đã được duyệt. Phần trăm báo cáo tuần là giá trị do Leader khai báo, được hiển thị riêng.
- Warning flags nhóm/thành viên: supervisor hoặc Admin tạo/gỡ; Overview trả badges và nội dung lý do.

## 5. Học kỳ và review

Trước khi tạo review, Admin cấu hình ngày đầu của tuần 1:

```http
PUT /api/v1/semesters/Fall2026
Content-Type: application/json

{ "startDate": "2026-09-07" }
```

- Tuần tính theo ngày địa phương `Asia/Ho_Chi_Minh`, mỗi tuần 7 ngày bắt đầu từ `startDate`.
- Review 1: tuần 3 hoặc 4. Review 2: tuần 7. Review 3: tuần 14.
- `POST /api/v1/reviews/clone` hỗ trợ dịch lịch Review 1 sang Review 2. `offsetDays` vẫn phải cho kết quả nằm trong tuần 7; toàn bộ thao tác rollback nếu một lịch không hợp lệ.
- Không được đổi ngày bắt đầu học kỳ sau khi có review, tránh làm sai các lịch đã lập. Học kỳ cũ có review nhưng chưa có calendar có thể cấu hình qua API nếu toàn bộ lịch hiện có nằm đúng tuần theo ngày bắt đầu đó; nếu không, cần đối chiếu và điều chỉnh dữ liệu lịch trước.
- Review 3 có đúng 3 người và một chủ tịch. Chủ tịch/Admin ghi kết quả phân loại.
- `REVISE_BEFORE_DEFENSE_1` bắt buộc có deadline tương lai. Sau deadline, không được xác nhận hoàn tất để quay lại Bảo vệ 1; Overview chuyển sang Bảo vệ 2.

## 6. Bảo vệ

- Có thể tạo lịch riêng hoặc `POST /api/v1/defenses/rolling` để xếp nhóm liên tiếp cùng phòng/hội đồng, theo `durationMinutes` và `breakMinutes`.
- Điều kiện tham dự lấy từ kết quả Review 3. Lần 1 không đạt được đăng ký lần 2; lần 2 không đạt chuyển nhóm sang FAILED; đạt chuyển COMPLETED.
- Chặn trùng phòng, người chấm giữa review và defense; bảo vệ cùng một slot thời gian được kiểm tra trong transaction giữ khóa lịch dùng chung giữa các instance.
- `DEFENSE_MAX_PARALLEL` mặc định 5, kiểm tra số phiên thực sự đồng thời tại các mốc bắt đầu. Hệ thống không tự chia lịch vào giờ hành chính; Admin chọn giờ bắt đầu và danh sách từng đợt.

## Thông báo và tích hợp frontend

- Domain event được lưu vào `domain_event_outbox` cùng transaction nghiệp vụ, gửi sau commit, và retry nếu bộ xử lý hoặc broker lỗi. Giữ nguyên event ID để chống tạo notification/email trùng. Retry tăng dần, tối đa một giờ giữa các lần thử.
- Email tiếp tục dùng `email_outbox` với tối đa 5 lần gửi SMTP. Khi `MAIL_ENABLED=false`, email chỉ được ghi log; phải cấu hình SMTP và bật cờ để gửi thật.
- Email gửi tới Leader, CC các thành viên và supervisor, có mã nhóm, đề tài, kết quả, feedback và deadline nếu sự kiện có deadline.
- SSE: `GET /api/v1/notifications/stream?access_token=...`, event tên `notification`. Frontend nhận event rồi gọi lại `GET /api/v1/groups/{groupId}/overview` và danh sách bài nộp nếu loại sự kiện là `DOCUMENT_FEEDBACK`. Khi kết nối lại, gọi REST để đồng bộ vì SSE không phải nhật ký replay.
- Backend này không chứa màn hình Dashboard; frontend cần tích hợp các API/luồng điều hướng trên.

## Chạy và nâng cấp

1. Cấu hình PostgreSQL, `JWT_SECRET`, `GOOGLE_CLIENT_IDS`, `ALLOWED_EMAIL_DOMAIN`, `CORS_ALLOWED_ORIGINS`.
2. Cấu hình `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` nếu gửi email thật. Cả hai Docker Compose đã truyền các biến này vào app.
3. Khởi động ứng dụng; Flyway tự chạy migration V10 để thêm feedback, calendar, khóa lịch và domain event outbox. Không sửa migration cũ.
4. Provision tài khoản, nhóm và Leader; tạo calendar trước khi đặt review. Tài khoản Student hiện có được giữ để nhận CC nhưng không còn đăng nhập được.
5. Chạy `mvn test` hoặc `mvn verify` với Java 17. H2 và PostgreSQL embedded đều kiểm tra quy trình; kiểm thử hạ tầng S3/SQS/Redis cần `INFRA_TESTS=true` và các service tương ứng.

Để kiểm tra trên máy đang chỉ có JDK 27, bộ test Mockito hiện tại cần `mvn "-DargLine=-Dnet.bytebuddy.experimental=true" test`; môi trường build chính vẫn là Java 17.
