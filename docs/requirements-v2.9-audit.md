# Đối chiếu backend với yêu cầu Website ghép nhóm v2.9

Ngày đối chiếu: 20/10/2026. Nguồn: [nguyên văn tài liệu được cung cấp](requirements/team-formation-v2.9.txt).

## Kết luận và phạm vi

Backend trước sửa chưa đúng v2.9: vẫn có nhiều môn và đăng nhập mật khẩu tùy cấu hình; Moderator bị giới hạn
sau deadline; chưa có gộp nhóm/chỉ định Leader trực tiếp; nguồn tài khoản giảng viên chưa được xác minh;
file Excel còn cột Môn và tên file theo mô hình nhiều môn. Các điểm này đã được sửa.

Đây là repository Spring Boot backend. Báo cáo xác nhận hành vi API, dữ liệu và kiểm thử trong repository này;
không xác nhận giao diện frontend, tài khoản Google thật, SMTP thật hoặc môi trường đang triển khai.
Các đề xuất trong tài liệu được giữ theo cơ chế hiện có khi không mâu thuẫn quy tắc đã chốt.
H1 và H4 vẫn cần mentor/nhà trường trả lời; không tự suy ra timeline chính thức.

## Bảng đối chiếu

| Mục tài liệu | Trước sửa | Sau sửa / nơi thực hiện |
|---|---|---|
| 1. Chỉ Capstone, cùng ngành | Còn tạo môn bất kỳ | Chỉ CAP / Capstone Project; tự tạo khi tạo học kỳ; ràng buộc duy nhất mỗi học kỳ ở DB. `SubjectService`, V19. Giữ tên bảng và đường dẫn `subjects` để tương thích API. |
| 2. Vai trò | Đã bỏ sub moderator; tài khoản giảng viên tạo tay có thể được gán | Chỉ giảng viên đã nhập, đang hoạt động mới được gán; quyền kiểm tra theo học kỳ tại `Access`. Sinh viên/giảng viên phải đến từ nhập danh sách. |
| 3. Học kỳ, ngành, phạm vi | Có mã kỳ, trạng thái tự tính, lưu trữ, phạm vi, deadline | Giữ các cơ chế có sẵn; cấu hình Capstone tự động, kỳ học mặc định 8 (phương án cấu hình, không phải kết luận của nhà trường), Moderator sửa khi chưa mở và chưa có nhóm. Phạm vi mở ngay khi bổ sung đủ điều kiện; scheduler xử lý mốc thời gian. |
| 3.4–3.5. Deadline, gia hạn, mở lại | Đã có vòng đời, khóa một lần, ngưỡng dưới 3, gia hạn 48 giờ | Giữ hủy Apply/Invite, chuyển yêu cầu rời thành khiếu nại, không khôi phục nhóm/đơn cũ; gỡ cờ chốt khi mở lại, giữ thời điểm trước. `ScopeService`. |
| 4. Nhập danh sách | Đã có preview/commit, đối chiếu MSSV + kỳ, báo lỗi, lịch sử | Bổ sung phát hiện email trùng trong file; không nhận kỳ học như 8.5 hoặc chuỗi sai; chặn thêm sinh viên bằng ngành mới sau khi ghép nhóm đã mở; chặn sửa danh sách đã chốt. Không tạo ngành từ dòng bị lỗi. |
| 4.2. Danh sách giảng viên | Vai trò LECTURER được coi như đã nhập | Bổ sung `lecturerImported`, chỉ nhập danh sách thiết lập cờ; kiểm tra cả đăng nhập và phân công. Đổi email giảng viên bằng quản lý tài khoản yêu cầu xác nhận lại qua nhập file. |
| 4.3. Quản lý danh sách | Có API sinh viên/nhóm/chưa có nhóm, tìm kiếm, lọc, phân trang sinh viên | Giữ API, bổ sung bảo vệ dữ liệu đã chốt khi chỉnh sửa sinh viên. Giao diện ba tab chưa được kiểm tra vì không có frontend trong repo này. |
| 5. Điều kiện sinh viên | Có UNDECIDED/ELIGIBLE/INELIGIBLE, tình trạng nhóm tự tính, cờ chốt | Giữ loại khỏi nhóm, hủy yêu cầu, chọn Leader sau khi loại cả lô, không trả về nhóm khi gỡ cờ; thêm khóa phạm vi khi đổi điều kiện để không xen vào thao tác chốt; sinh viên không đủ điều kiện hoặc thuộc danh sách đã chốt không sửa hồ sơ. |
| 6.1–6.3. Nhóm, Apply, Invite | Có 1–5 thành viên, tối đa 3 Apply, TTL, khóa riêng với đầy | Giữ duyệt Apply vào nhóm ngay, lời mời không giữ chỗ, kiểm tra lại hạn/sức chứa; thêm kiểm tra điều kiện tại điểm ghi thành viên và khóa khi tạo lời mời. |
| 6.4–6.5. Rời nhóm, chuyển Leader | Có rời nhóm, khiếu nại, chuyển Leader có đồng ý, chọn người vào sớm nhất | Bổ sung kiểm tra học kỳ mới nhất cho các thao tác Leader; giữ chuyển Leader sau chốt, lịch sử và thông báo; làm mới ghi chú thay đổi sau chốt khi chốt lại. |
| 7.1. Moderator toàn quyền | Chỉ điều phối khi LOCKED; thiếu gộp và đổi Leader trực tiếp | Cho phép thêm/đưa ra/chuyển/lập/gộp/giải tán/chỉ định Leader tới trước chốt, kể cả nhóm Leader khóa; vẫn kiểm tra cùng ngành, điều kiện và tối đa 5. Sau chốt phải mở lại. |
| 7.2. Chốt danh sách | Đã chặn nhóm dưới 3 và yêu cầu rời/khiếu nại tồn đọng | Giữ chỉ cảnh báo sinh viên lẻ, chỉ chốt thành viên nhóm hợp lệ, ngoại lệ chuyển Leader sau chốt. Bổ sung bảo vệ chỉnh sửa danh sách/điều kiện trước thao tác chốt đồng thời. |
| 7.3. Thống kê | Dùng `Snapshot`, cùng nguồn với Excel | Giữ tổng = có nhóm + chưa có nhóm, không đủ điều kiện đếm riêng, nhóm vừa đầy vừa khóa đếm cả hai; đọc trong giao dịch REPEATABLE_READ. |
| 8.1–8.2. Hồ sơ, tìm kiếm, Like | Có hồ sơ riêng tư, tìm trong phạm vi; lời mời có thể lộ hồ sơ riêng tư | Chỉ Apply còn chờ hoặc đồng đội hiện tại mới cho thành viên nhóm xem hồ sơ riêng tư; Invite riêng không cấp quyền. Like và thay đổi yêu cầu kiểm tra quyền hành động hiện tại. |
| 8.3. Thông báo | Có web/SSE, email outbox, thông báo nghiệp vụ | Giữ các sự kiện bắt buộc; thêm thông báo thay đổi khi Moderator gộp nhóm/chỉ định Leader. Email thực tế cần cấu hình SMTP. |
| 9. Google theo danh sách | Có thể bật lại mật khẩu, thiếu kiểm chứng danh sách giảng viên | Mật khẩu luôn bị từ chối kể cả cờ cũ bật; Swagger ẩn API mật khẩu; Google kiểm tra danh sách, trạng thái tài khoản. Giữ học kỳ cũ chỉ xem và kỳ gần nhất trước. |
| 10. Excel | Hai sheet, định dạng text, bộ lọc, cố định hàng, không xuất hồ sơ; còn cột/tên file nhiều môn | Bỏ cột Môn; tên `Danh_sach_ghep_nhom_<HocKy>_<Nganh hoặc TatCa>_<yyyyMMdd_HHmmss>.xlsx`; giữ sắp xếp, trạng thái đầy + khóa, ghi chú chốt/mở lại/chuyển Leader. |
| 11. Lịch sử | Có nhập file, giai đoạn, deadline, phân công, điều phối, điều kiện | Bổ sung lịch sử gộp nhóm và Moderator chỉ định Leader. `AuditService`, `AuditAction`. |
| 12. Đồng thời, phân quyền | Có khóa hàng và chỉ mục ngăn trùng nhóm/Leader, kiểm thử PostgreSQL | Thêm tuần tự cấp mã nhóm trong cùng phạm vi, kiểm tra điều kiện tại điểm thêm thành viên, khóa phạm vi khi đổi điều kiện/điều phối và sửa danh sách. |
| 13–14. Thuật ngữ, quy tắc cũ | README/Swagger còn mô tả mật khẩu, nhiều môn, quản lý tiến độ | Cập nhật README/Swagger và bỏ cấu hình mật khẩu trong các cấu hình triển khai đang dùng. Tài liệu v2.8 và diagram cũ giữ làm lịch sử, không dùng làm nguồn yêu cầu v2.9. |

## API mới và tương thích frontend

- `POST /api/v1/groups/{groupId}/moderation/merge`, body `{"sourceGroupId":"UUID"}`: gộp toàn bộ nhóm nguồn vào nhóm đích trong một giao dịch; quá 5 thì không chuyển ai.
- `POST /api/v1/groups/{groupId}/moderation/leader`, body `{"userId":"UUID"}`: chỉ định thành viên hiện tại làm Leader, không cần luồng đồng ý của sinh viên.
- Tạo học kỳ đã có sẵn Capstone. Frontend lấy ID qua `GET /api/v1/semesters/{semester}/subjects`; không tạo thêm môn.
- `PUT /api/v1/subjects/{subjectId}` cấu hình `targetTerm` trước khi mở ghép nhóm; `requestTtlHours` vẫn cấu hình được. Không đổi tên/mã sang môn khác.
- Đăng nhập qua `POST /api/v1/auth/google` với `idToken`. Endpoint mật khẩu cũ chỉ trả `403 GOOGLE_LOGIN_REQUIRED` để client cũ nhận lỗi rõ ràng.
- Các endpoint đọc và các tham số `subjectId` hiện có được giữ để frontend chuyển tiếp thuận tiện, nhưng dữ liệu chỉ có Capstone.

## Nâng cấp dữ liệu và cấu hình

V19 thêm cờ nguồn giảng viên, ràng buộc chỉ Capstone, một cấu hình mỗi kỳ và sĩ số 3–5.
V19 tự bổ sung Capstone/phạm vi/enrollment còn thiếu cho dữ liệu học kỳ đã có.
Nếu DB cũ có môn khác hoặc nhiều môn trong một kỳ, migration dừng với thông báo cụ thể;
không tự xóa hoặc gộp mất dữ liệu. Cần đối chiếu dữ liệu đó trước khi nâng cấp.

Admin phải nhập lại danh sách giảng viên cũ để xác nhận nguồn tài khoản; không suy ra đã nhập chỉ từ vai trò.
Seed V2001 đánh dấu rõ các giảng viên mẫu trong môi trường dev và bổ sung cấu hình học kỳ SP27.
Không sửa nội dung các migration đã tồn tại. Chưa áp dụng migration lên DB đang vận hành.

`GOOGLE_CLIENT_IDS` cần chứa client ID dùng ở frontend. Admin cần email Google thực tế đã được quản lý trong
hệ thống; tài khoản mẫu `admin@fpt.edu.vn` không thể dùng mật khẩu để đăng nhập.
Thông báo email thực tế cần `MAIL_ENABLED=true` và cấu hình SMTP.

## Xác minh

Các kiểm thử tích hợp gồm tạo/nhập học kỳ tự sinh Capstone và phạm vi, chặn môn khác, xác minh giảng viên đã nhập,
Moderator điều phối nhóm khóa trước deadline, gộp nhóm và rollback khi quá sĩ số, chỉ định Leader,
chặn sửa sau chốt nhưng vẫn chuyển Leader, hồ sơ riêng tư qua lời mời, lỗi email/kỳ học khi nhập,
deadline/chốt/mở lại, thống kê/Excel, thông báo và hai thao tác vào nhóm đồng thời.
Các luồng mới được chạy cả trên H2 và PostgreSQL 16.4 cùng các Flyway migration thật.

Kết quả chạy cuối: `mvn verify -DargLine=-Dnet.bytebuddy.experimental=true` thành công; 80 kiểm thử,
77 thành công, 0 lỗi, 0 thất bại, 3 bỏ qua. Backend đã đóng gói thành công.
Các kiểm thử hạ tầng SQS/Redis chỉ chạy khi `INFRA_TESTS=true` và có dịch vụ tương ứng;
Google/SMTP thật nằm ngoài bộ kiểm thử cục bộ. Không có thay đổi lên DB đang vận hành.
