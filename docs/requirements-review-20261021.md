# Đối chiếu project và nâng cấp API — 21/10/2026

Nguồn hiện hành: [nguyên văn tài liệu người dùng gửi lại ngày 21/10](requirements/team-formation-v2.9-revised-20261021.txt).
Tài liệu vẫn mang số phiên bản 2.9 nhưng thay đổi nhiều quyết định so với bản nhận ngày 20/10;
báo cáo này thay thế kết luận trong `requirements-v2.9-audit.md`.

## Kết luận

**Trước lần sửa này, project chưa đúng tài liệu mới.** Các điểm sai chính là mọi vai trò chỉ được dùng Google,
Moderator phải lấy từ danh sách giảng viên, không được điều phối sau chốt, điều kiện sinh viên bị chặn sau chốt,
cờ chốt nằm ở sinh viên, còn API lưu trữ/trạng thái học kỳ và lịch sử thao tác, file nhập bắt buộc cột điện thoại,
chưa áp dụng điều kiện ngay trong file sinh viên và chưa cảnh báo nhóm thiếu người trước khi xuất Excel.

Các sai lệch backend nêu trên đã được sửa và có kiểm thử. Không kết luận toàn bộ website hoàn thành 100%:
repository này chỉ chứa backend. Phần giao diện và kiểm chứng dịch vụ ngoài hệ thống được liệt kê riêng bên dưới.

## Đối chiếu theo yêu cầu

| Mục | Kết quả sau nâng cấp | Bằng chứng chính |
|---|---|---|
| 1. Chỉ Capstone, chỉ cùng ngành | Có một cấu hình CAP mỗi kỳ; tự tạo phạm vi theo ngành, kiểm tra cùng phạm vi khi Apply/Invite/thêm/chuyển/gộp | `SubjectService`, `RosterSync`, `GroupCore`, `ModerationService`, ràng buộc V19 |
| 2, 4.2, 9. Admin/Moderator dùng mật khẩu | Admin được tạo sẵn; Admin tạo Moderator qua API. Không tạo Admin hoặc sinh viên bằng API tài khoản. Mật khẩu BCrypt, đăng nhập có giới hạn số lần sai | `AuthService`, `UserService`, `UserController`; kiểm thử tạo tài khoản, đổi mật khẩu, đăng nhập |
| 2, 3.6. Phân công theo học kỳ | Một `moderatorId` trong học kỳ. Moderator chỉ đọc/điều phối học kỳ được gán; quyền mới áp dụng ngay khi thay người. Admin có quyền quản trị toàn bộ | `Access`, `SemesterService`, `SubjectService`, `SemesterRosterController`; kiểm thử Moderator không được gán bị từ chối |
| 3.1. Học kỳ không có trạng thái | Response không còn `status`, `archivedAt`; bỏ API archive/unarchive, không ẩn học kỳ theo trạng thái. Giữ mã, thời gian, số sinh viên/nhóm, tìm kiếm/lọc/sắp xếp. Không xóa kỳ có sinh viên | `SemesterService`, `SemesterController`, `MeController` |
| 3.1, H4. Timeline do Moderator đặt | Ngày mở/deadline lấy từ cấu hình; không áp timeline tuần 5 hay lịch cố định. Phạm vi tự mở khi đủ điều kiện; deadline riêng/gia hạn theo quy tắc | `ScopeService`; kiểm thử mở ngay khi bổ sung đủ dữ liệu, deadline và gia hạn |
| 3.2. Nhập kỳ 8, làm đồ án kỳ 9 | File nhập báo lỗi kỳ khác 8; không thay `targetTerm` sang kỳ khác. Dưới 80% tín chỉ bị đánh dấu INELIGIBLE nếu có dữ liệu tỷ lệ | `StudentImportService`, `ImportedEligibility`; kiểm thử kỳ 9 bị loại, tỷ lệ 79/80 và thiếu xác nhận |
| 3.3, H1. Không đổi ngành | Sửa ngành bị từ chối cả khi đang chuẩn bị, qua API chỉnh sửa và nhập lại. Đã đổi ngành ở kỳ trước được phòng đào tạo xác nhận qua điều kiện/lý do | `RosterService`, `StudentImportService`, `EligibilityImportService` |
| 3.4–3.5. Các giai đoạn, deadline | Giữ PREPARING → OPEN → LOCKED → FINALIZED; tại deadline hủy đơn/mời, chuyển yêu cầu rời sang khiếu nại, giải tán nhóm dưới 3 đúng lúc chuyển giai đoạn. Gia hạn không phục hồi đơn/nhóm cũ | `ScopeService`; kiểm thử deadline, mở lại/gia hạn và chốt trên H2/PostgreSQL |
| 4.1. Nhập nhiều lần, preview trước commit | Điện thoại không bắt buộc cả cột và giá trị. Kiểm tra MSSV/email trùng, dữ liệu sai, thiếu cột. Đối chiếu MSSV + kỳ, giữ người vắng trong file mới. Đọc điều kiện/tỷ lệ tín chỉ ngay trong file sinh viên | `SpreadsheetReader`, `StudentImportService`, `ImportedEligibility` |
| 4.1. Danh sách sau khi mở | Không thêm người mới kể cả bằng một ngành mới, không đổi ngành/kỳ. Cho sửa thông tin liên hệ; điều kiện áp dụng cả sau chốt | Kiểm thử danh sách đã mở, bất biến ngành, nhập điều kiện sau chốt |
| 4.3. API phục vụ ba tab | API theo học kỳ cho tất cả sinh viên, nhóm, và sinh viên chưa nhóm bằng `hasGroup=false`; tìm kiếm/lọc/phân trang. Nhóm có `maxSize=2`, lọc `finalized` | `SemesterRosterController`; giao diện ba tab chưa được xác nhận trong repository này |
| 5. Trạng thái sinh viên | Chỉ eligibility và tình trạng nhóm tự tính. Không còn cờ chốt trong DTO sinh viên hoặc DTO môn của sinh viên | `Enrollment`, `RosterService.StudentRow`, `MeController`; kiểm thử JSON không có cờ chốt sinh viên |
| 5.2. Không đủ điều kiện | Được nhập/đổi tay ở mọi giai đoạn. Tự ra nhóm, hủy yêu cầu, chọn Leader theo người vào sớm nhất còn lại, giải tán nếu rỗng. Gỡ cờ không trả về nhóm cũ; sinh viên không đủ điều kiện chỉ xem | `EligibilityService`, `GroupCore`, `StudentGate`, `ProfileController` |
| 6.1–6.3. Nhóm/Apply/Invite | Giữ tạo nhóm 1 người, tối đa 5, tối đa 3 Apply chờ, lời mời không giữ chỗ; duyệt Apply vào ngay; kiểm tra hạn/phạm vi/sức chứa khi nhận. Giữ đầy + khóa độc lập | `JoinRequestService`, `GroupCore`; kiểm thử cạnh tranh hai lượt nhận và hai nhóm cho một sinh viên |
| 6.3, 7.2. Chốt thuộc nhóm | `student_groups.finalized/finalized_at`; response nhóm và nhóm trong `/me/semesters` có trạng thái chốt. Chốt nhóm hợp lệ, mở lại gỡ cờ nhóm. Excel giữ cả thông tin đầy, khóa, đã chốt | V20, `StudentGroup`, `ScopeService`, `GroupViews`, `Snapshot` |
| 6.4. Rời nhóm/khiếu nại | Giữ rời ngay khi cho phép, duyệt trong nhóm khóa, khiếu nại. Moderator quyết định thì báo cả sinh viên và Leader, kể cả từ chối. Chuyển Leader cần người nhận đồng ý; sau chốt vẫn được chuyển trong nhóm | `LeaveRequestService`, `LeaderTransferService`; kiểm thử thông báo khi Moderator từ chối |
| 6.5. Leader tự động | Người vào sớm nhất còn lại, tiêu chí phụ MSSV cố định khi trùng thời điểm. Xử lý cả lô loại người trước khi chọn lại | `GroupMemberRepository`, `EligibilityService`, `GroupCore` |
| 7.1. Moderator toàn quyền | Thêm/đưa ra/chuyển/gộp/giải tán/tạo nhóm/chỉ định Leader ở mọi giai đoạn, kể cả sau chốt, không cần Leader/người nhận đồng ý. Vẫn chặn khác ngành, không đủ điều kiện, quá 5, trùng nhóm | `ModerationService`; kiểm thử nhóm khóa, gộp rollback, điều phối sau chốt, quyền theo học kỳ |
| 7.1. Nhóm tụt dưới 3 sau deadline/chốt | Không tự giải tán ngoài thời điểm khóa; không chặn điều phối. Báo khi chuẩn bị xuất nhưng vẫn xuất. Nhóm không còn ai vẫn giải tán theo 6.4/5.2 | `GroupCore`, `ExcelExportService`; kiểm thử nhóm đã chốt tụt còn 2 sau file điều kiện |
| 7.2. Chốt | Chặn nhóm dưới 3 và yêu cầu rời/khiếu nại chưa xong; sinh viên lẻ chỉ cảnh báo. Sinh viên/Leader bị chặn sửa sau chốt, ngoại lệ chuyển Leader. Moderator vẫn thao tác được | `ScopeService`, các kiểm tra phía server; không dùng cờ sinh viên để phân quyền |
| 7.3. Thống kê | Chung nguồn snapshot với Excel, REPEATABLE_READ; có số nhóm đã chốt bên cạnh đầy/khóa/sĩ số; tổng sinh viên = có + chưa nhóm | `StatisticsService`, `Snapshot`; kiểm thử số liệu/Excel và nhóm đã chốt |
| 8.1–8.2. Hồ sơ/tìm kiếm/Like | Private chỉ hiện tên/MSSV; không lọc bằng kỹ năng ẩn. Apply chờ/cùng nhóm cấp quyền xem; lời mời riêng không cấp quyền. Chỉ Leader tìm ứng viên, giới hạn cùng kỳ/ngành | `ProfileView`, `SearchService`, `JoinRequestService`; kiểm thử private và cấp/thu hồi quyền qua đơn |
| 8.3. Thông báo | Có web/SSE và email outbox cho các sự kiện bắt buộc. Cảnh báo trước xuất vẫn gửi khi chính Moderator là người xuất | `NotificationHandler`, `Notifier`; kiểm thử in-process và email outbox, chưa xác nhận SMTP thật |
| 9. Sinh viên Google theo danh sách | Chỉ STUDENT có trong roster dùng Google; FPT bị từ chối; Admin/Moderator không dùng Google. Tài khoản bị khóa không đăng nhập. Kỳ gần nhất được ưu tiên, kỳ cũ giữ nhóm và chỉ xem | `AuthService`, `StudentGate`, `MeController`, Google verifier giả lập trong kiểm thử |
| 10. Excel | Hai sheet đúng cột/tên file, không gộp ô, MSSV/điện thoại là text, sắp xếp/Leader trước, bộ lọc/cố định hàng, không xuất kỹ năng/hồ sơ. Cảnh báo nhóm dưới 3 không chặn tải | `ExcelExportService`; bỏ ghi chú lịch sử thay đổi sau chốt khỏi header |
| 11. Lịch sử để làm sau | Gỡ endpoint audit, lịch sử nhập file, nhập giảng viên; không ghi thêm operation audit. Giữ bảng/cột cũ để tương thích migration/seed, không dùng làm chức năng hiện hành | `AuditService.record` không ghi; không sửa migration cũ hoặc xóa dữ liệu lịch sử |
| 12. Phân quyền/đồng thời | Kiểm tra phía server; khóa phạm vi/enrollment/nhóm và chỉ mục duy nhất. Token cũ bị từ chối khi email được sửa/gán lại, tránh token nhận danh tính khác | `Access`, `Locks`, `JwtAuthenticationFilter`, kiểm thử PostgreSQL cạnh tranh |

## API mới hoặc nâng cấp

| API | Quyền / hành vi |
|---|---|
| `POST /api/v1/auth/login` | Admin/Moderator dùng email + password; sinh viên nhận `GOOGLE_LOGIN_REQUIRED` |
| `POST /api/v1/auth/google` | Chỉ sinh viên có trong danh sách; Admin/Moderator nhận `PASSWORD_LOGIN_REQUIRED` |
| `POST /api/v1/users/moderators` | Admin tạo Moderator; body email, fullName, password, phone tùy chọn |
| `PUT /api/v1/users/{id}/password` | Admin đặt/đổi mật khẩu Moderator, hỗ trợ chuyển tài khoản giảng viên cũ; không cấp mật khẩu sinh viên |
| `PUT /api/v1/semesters/{semester}/moderator` | Body `moderatorId`; vẫn nhận tên cũ `lecturerId` để tương thích |
| `GET /api/v1/semesters/{semester}/students` | Tìm `q`, lọc `majorId`, `eligibility`, `hasGroup`, phân trang `page/size` |
| `GET /api/v1/semesters/{semester}/groups` | Tìm/lọc ngành/trạng thái, `maxSize=2`, `finalized`, phân trang; tối đa 100 dòng/trang, sắp theo ngành/mã nhóm |
| `GET /api/v1/semesters/{semester}/scopes` | Phạm vi theo ngành, không phải tìm ID môn trước |
| `GET /api/v1/semesters/{semester}/export-check` | Trả danh sách nhóm dưới 3 và cảnh báo, gửi thông báo Moderator; `canExport=true` |
| `GET /api/v1/semesters/{semester}/export` | Vẫn xuất ngay kể cả nhóm dưới 3; cũng gửi cảnh báo nếu chưa gọi export-check |
| Các API moderation/eligibility hiện có | Dùng được sau chốt; giữ giới hạn sĩ số/ngành và quyền theo học kỳ |

Response sinh viên bỏ `finalized`; response nhóm thêm `finalized`, `finalizedAt`.
Response học kỳ bỏ `status`, `archivedAt`. `LECTURER` là tên role nội bộ/API cho tài khoản **Moderator**;
không có nghiệp vụ danh sách giảng viên. Những client đang dùng các trường/endpoint cũ phải cập nhật.

## File nhập sinh viên

Bắt buộc: `MSSV,Họ tên,Email,Kỳ,Ngành`. Kỳ phải là 8.
Tùy chọn: `Số điện thoại,Điều kiện,Lý do,Tỷ lệ tín chỉ`.

- `Điều kiện`: ELIGIBLE / INELIGIBLE / UNDECIDED hoặc nhãn tiếng Việt tương ứng.
- Thiếu xác nhận điều kiện: người mới là UNDECIDED, vẫn được ghép nhóm; nhập lại không có cột điều kiện không tự xóa kết luận trước.
- `Tỷ lệ tín chỉ` là số 0–100, hỗ trợ phần thập phân; dưới 80 ghi INELIGIBLE và lý do. Tỷ lệ từ 80 trở lên, nếu không có xác nhận khác, vẫn UNDECIDED vì chưa chứng minh các điều kiện còn lại.
- Việc đã đổi ngành ở kỳ trước được ghi bằng INELIGIBLE và lý do trong file; hệ thống không tự biết lịch sử đào tạo ngoài dữ liệu đã cung cấp.
- File điều kiện riêng tiếp tục nhận MSSV, Điều kiện (trống mặc định INELIGIBLE), Lý do; áp dụng cả sau chốt.

## Những gì chưa hoàn thành hoặc chưa kiểm chứng

### Chưa hoàn thành trong phạm vi kiểm tra website

1. **Frontend theo tài liệu mới:** không có mã frontend trong repository này. Chưa sửa/xác nhận giao diện đăng nhập phân vai, màn tạo Moderator, ba tab quản lý, phân trang, trạng thái chốt của nhóm và bước cảnh báo trước xuất. Backend đã có API hỗ trợ, nhưng client cũ có thể chưa dùng.
2. **Toàn bộ diagram/tài liệu thiết kế cũ:** ERD hoạt động đã cập nhật. Bộ C4/sequence/blueprint cũ chưa được vẽ lại toàn bộ theo bản tài liệu gửi lại, đúng ghi chú mục 12 của requirement. Không dùng các bản cũ làm mô tả API hiện hành.

### Đã có chức năng nhưng chưa kiểm chứng tích hợp thật

3. **Google đăng nhập bằng tài khoản thật trên frontend:** kiểm thử xác thực dùng Google verifier giả lập, không chứng minh màn đăng nhập thực tế/OAuth origin hoạt động. Cần đúng client ID và email Google thực trong roster.
4. **Email tới hộp thư thật:** đã kiểm thử sinh sự kiện và email outbox; môi trường debug đang tắt SMTP. Chưa xác nhận việc nhận email thật ở Gmail.
5. **SQS/Redis nhiều instance:** 3 kiểm thử hạ tầng cần dịch vụ ngoài được bỏ qua; luồng in-process và PostgreSQL đã được kiểm chứng. Chưa chứng minh end-to-end trên cụm nhiều instance.
6. **Dữ liệu thật của phòng đào tạo:** chưa có file thực để đối chiếu toàn bộ header/giá trị/tỷ lệ tín chỉ và tình trạng đổi ngành. Không tự kết nối hệ thống đào tạo hoặc suy ra 80% nếu file không cung cấp dữ liệu.

Lịch sử thao tác là **ngoài phạm vi/để làm sau theo mục 11**, không phải lỗi chưa triển khai của bản hiện hành.
Kho đề tài, hướng dẫn/chấm điểm, chat, môn khác và ghép liên ngành cũng không thuộc phạm vi.
H1/H4 đã có câu trả lời trong tài liệu mới; không còn ghi là câu hỏi chờ mentor.

## Migration và xác minh

V20 thêm trạng thái chốt của nhóm, chuyển trạng thái hiện có từ phạm vi đã chốt, bỏ sử dụng cờ chốt sinh viên,
trạng thái lưu trữ và nguồn nhập giảng viên. Xóa mật khẩu sinh viên cũ; V2002 xử lý cùng việc cho seed demo bất biến.
Các bảng/cột cũ giữ để không phá seed/migration đã phát hành; chúng không được dùng trong phân quyền/response mới.

Backend kiểm tra `capstone-debug` tại cổng 8081 được sao lưu trước khi nâng cấp, trong
`target/db-backups/capstone-debug-before-revised-v29-20261021.dump`. Backend cũ ở 8080 không được nâng cấp trong lần này.

Kết quả `mvn verify`: **95 kiểm thử, 92 đạt, 0 lỗi, 0 thất bại, 3 bỏ qua**; đóng gói thành công.
Các kiểm thử mới chạy cả H2 và PostgreSQL 16.4 với Flyway thật: tài khoản/mật khẩu theo vai trò,
quyền học kỳ, file không có điện thoại/kỳ sai/80% tín chỉ, cờ chốt nhóm, nhập điều kiện sau chốt,
Moderator thao tác sau chốt, cảnh báo xuất và thông báo cho chính người xuất, khiếu nại báo cả Leader,
và token sau sửa/gán lại email.

Kiểm tra HTTP sau khi cập nhật backend `http://localhost:8081`:

- Swagger trả 200, OpenAPI có 77 đường dẫn.
- Admin đăng nhập email/mật khẩu thành công; các API phân trang sinh viên/nhóm theo FA26 trả dữ liệu.
- Response nhóm có `finalized`; response sinh viên không có `finalized`; học kỳ không có `status`.
- Swagger có API tạo Moderator mới, không còn API nhập giảng viên hoặc audit.
- Docker app/PostgreSQL đều healthy; V20 và V2002 đã áp dụng thành công trên DB kiểm tra.
- File PDF có sẵn từ trước không được sửa.
