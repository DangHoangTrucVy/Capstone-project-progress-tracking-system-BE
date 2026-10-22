# Thời gian demo — 22/10/2026

Theo lựa chọn của người dùng: rút hạn Apply/Invite; không tự đưa deadline học kỳ về 15 phút.

- API cấu hình dùng `requestTtlMinutes` thay `requestTtlHours`; 10 hoặc 15 phút đều hợp lệ.
- Hạn mặc định cho cấu hình Capstone mới trong backend chính: 15 phút. Hạn tối thiểu khi đặt/gia hạn deadline: 10 phút.
- Backend chính mặc định 15 phút cho đơn/mời, 10 phút báo trước deadline; có thể ghi đè bằng biến môi trường.
- FE có ô nhập phút, nút 10/15 phút và hiển thị thời điểm hết hạn của đơn/mời.
- Đổi thời hạn qua API/FE chỉ ảnh hưởng đơn mới; đơn đã gửi không bị tự reset.
- Khi triển khai demo lần này, các cấu hình Capstone hiện có được đặt về 15 phút.
  Các đơn/mời đã gửi giữ nguyên thời gian hết hạn đã lưu. Không đổi ngày mở hoặc deadline ghép nhóm.
  Bước dự kiến rút ngắn đơn đang chờ bị kiểm duyệt tự động từ chối vì chưa có xác nhận riêng;
  không thực hiện bước đó, không sửa trực tiếp expiry của các đơn hiện có.
- Hết hạn được kiểm tra khi thao tác; tác vụ nền cập nhật trạng thái lưu trong DB mỗi khoảng 30 giây.
- V21 thêm `subjects.request_ttl_minutes`, chuyển dữ liệu cũ bằng số giờ × 60, không sửa migration đã áp dụng.
  Cột giờ lịch sử giữ lại để seed cũ vẫn hoạt động; runtime chỉ dùng cột phút.
- Trước nâng cấp đã sao lưu vào `target/db-backups/before-demo-timing-20261022.dump` (không đưa vào Git).

Kiểm tra: 99 backend tests, 96 đạt, 3 hạ tầng được bỏ qua, không lỗi; gồm PostgreSQL và migration V21/V22.
Kiểm thử mới xác nhận hạn Apply 10 phút, Invite 15 phút, không reset đơn cũ, từ chối thời gian sai,
phân quyền và deadline ngắn trong demo. Kiểm thử cấu hình mới cũng xác nhận mặc định 15 phút và báo trước deadline 10 phút.

Trở lại 48 giờ: lưu `requestTtlMinutes=2880` cho từng học kỳ đã đổi, đặt hai biến môi trường về 2880
(cần đặt rõ ràng), rồi khởi động lại backend. Bỏ biến môi trường không tự ghi đè thời hạn đã lưu theo học kỳ.

V22 chỉ đổi DEFAULT của cột phút thành 15 cho cấu hình tạo mới; giữ nguyên mọi giá trị và đơn đã lưu.
