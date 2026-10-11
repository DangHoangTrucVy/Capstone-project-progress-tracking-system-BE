# Kịch bản test BE: Invite Student, xuất Excel, Leader yêu cầu rời nhóm

Tài liệu này là phần BE của kịch bản kiểm thử thủ công. Mỗi ID (INV, EXP, LEA, REG) ứng với một test tự động trong
`src/test/java/com/capstone/tracking/group/InviteExportLeaderLeaveScenarioIntegrationTest.java`. Lớp
`...ScenarioPostgresTest` chạy lại toàn bộ trên PostgreSQL 16 thật, dựng bằng Flyway. Các bước chỉ FE kiểm được
(trạng thái nút, toast, tên file do trình duyệt lưu) được ghi rõ là **FE**.

```bash
mvn test -Dtest='InviteExportLeaderLeaveScenario*'   # 35 ca x 2 (H2 + PostgreSQL)
```

## Dữ liệu dựng sẵn (tạo mới cho mỗi test)

| Dữ liệu | Trạng thái |
|---|---|
| Admin A | `ADMIN` |
| Leader L1, nhóm G1 | 3 thành viên: L1, M1, M2 (vào nhóm qua Invite theo MSSV + Accept) |
| S1 | đủ điều kiện, chưa có nhóm |
| S2 | đã thuộc nhóm G3 |
| S3 | `eligible=false`, lý do "Thiếu tín chỉ" |
| Nhóm G2 (Leader L2) | đủ 5 thành viên |
| Học kỳ | `SEM` có dữ liệu; `EMPTY-*` không có dữ liệu |

## 2. Mời sinh viên bằng MSSV: `POST /api/v1/groups/{groupId}/invites`

| ID | Request | BE trả về / kiểm tra |
|---|---|---|
| INV-01 | `{"studentCode":"<MSSV S1>"}` | 201, `type=INVITE`, `status=PENDING`, `student.studentCode`; có trong `GET /groups/{id}/invites` và `GET /me/invites` |
| INV-02 | `"  s1xxxx  "` | 201: BE tự trim và không phân biệt hoa thường (FE vẫn nên chuẩn hóa trước khi gửi) |
| INV-03 | `"   "` hoặc `{}` | 400, không tạo lời mời. Việc vô hiệu hóa nút gửi do **FE** đảm nhận |
| INV-04 | MSSV không tồn tại | 404, `message` chứa MSSV |
| INV-05 | MSSV của S2 | 409 "already belongs to a group"; S2 vẫn ở G3 |
| INV-06 | MSSV của S3 | 403, `errorCode=NOT_ELIGIBLE`, `message` chứa lý do |
| INV-07 | mời lại S1 | 409; S1 chỉ có 1 lời mời PENDING |
| INV-08 | G2 (5 người) mời thêm | 409 "full" |
| INV-09 | gửi lặp 3 lần | đúng 1 lần 201, các lần còn lại 409. Trạng thái "Đang gửi..." do **FE** đảm nhận |
| INV-10 | S1 `POST /invites/{id}/accept` | 201; S1 vào G1; sĩ số từ 3 lên 4; `GET /groups/{id}` có `members[].studentCode` |
| INV-11 | L1 `POST /invites/{id}/revoke` | 200, `WITHDRAWN`; S1 accept thì nhận 409 |

## 3. Xuất Excel: `GET /api/v1/reports/export?semester=` (Admin)

Endpoint cũ `GET /api/v1/reports/handover/export` vẫn dùng được.

| ID | Kiểm tra |
|---|---|
| EXP-01/02 | 200, `Content-Type` là xlsx, `Content-Disposition: attachment; filename="Danh_sach_ghep_nhom_<HK>_yyyyMMdd_HHmmss.xlsx"` (giờ Việt Nam) |
| EXP-03/04 | Apache POI mở được file; có đúng 2 sheet theo thứ tự `Danh_sach_nhom`, `Chua_co_nhom`; header gồm Mã nhóm, Học kỳ, MSSV, Họ tên, Email, Vai trò |
| EXP-05 | G1 có 3 dòng, đúng học kỳ, cột "Số thành viên" = 3; dòng đầu là L1 với vai trò Leader; chỉ có 1 Leader; S1 nằm ở `Chua_co_nhom` |
| EXP-06 | MSSV `0…` là ô **chuỗi** và giữ số 0 đầu; tên tiếng Việt giữ nguyên; tên bắt đầu bằng `=` vẫn là chuỗi, không thành công thức. *BE không có trường số điện thoại nên file không có cột này.* |
| EXP-07 | Học kỳ không có dữ liệu: 200; `Danh_sach_nhom` chỉ có dòng header. `Chua_co_nhom` liệt kê mọi sinh viên ACTIVE chưa có nhóm **trong học kỳ đó** |
| EXP-08 | **FE**: chặn bấm lặp, hiển thị "Đang tạo file Excel...". BE không giữ trạng thái nên gọi nhiều lần không gây hại |
| EXP-09/10 | **FE**: chỉ lưu file khi status là 200 và `Content-Type` là xlsx; nếu không thì hiện lỗi. BE trả lỗi dưới dạng JSON, không bao giờ là file |
| EXP-11 | Không có token, token sai hoặc hết hạn: 401 kèm JSON; tài khoản không phải Admin: 403 |
| EXP-12 | Thêm S1 vào G1 rồi xuất lại: S1 xuất hiện ở G1 (sĩ số 4) và không còn ở `Chua_co_nhom` |

## 4. Leader yêu cầu rời nhóm

API:
- Leader gửi: `POST /api/v1/groups/{groupId}/leave-requests`, body `{"reason":"..."}`.
- Admin xem: `GET /api/v1/leave-requests?status=PENDING&leaderOnly=true`.
- Admin duyệt: `POST /api/v1/leave-requests/{id}/approve`, body tùy chọn `{"newLeaderUserId":"..."}`.
- Admin từ chối: `POST /api/v1/leave-requests/{id}/reject`.

| ID | Kiểm tra |
|---|---|
| LEA-01/02 | 201, `PENDING`, `leaderRequest=true`, có `reason`; L1 vẫn là Leader, nhóm vẫn 3 người |
| LEA-03/04 | Admin nhận đúng 1 thông báo `LEADER_LEAVE_REQUESTED`: `groupId` = G1, `entityId` = mã yêu cầu, `message` có tên L1, mã nhóm và chữ "Leader", `details` = lý do. Không có thông báo loại `LEAVE_REQUESTED` (của thành viên thường); M1 không nhận thông báo này |
| LEA-05 | `reason` rỗng, chỉ có khoảng trắng hoặc không gửi body: 400, không tạo yêu cầu. *Lưu ý: với thành viên thường, lý do vẫn không bắt buộc* |
| LEA-06 | 1001 ký tự: 400; đúng 1000 ký tự: 201 |
| LEA-07 | Gửi lại khi đang chờ: 409; chỉ có 1 yêu cầu và 1 thông báo cho Admin |
| LEA-08 | Gọi lại bằng token mới: yêu cầu vẫn `PENDING` trong danh sách của nhóm và trong hộp thư Admin |
| LEA-09 | L1 tự duyệt hoặc tự từ chối: 403 |
| LEA-10 | Admin từ chối: `REJECTED`; L1 vẫn là Leader; L1 nhận `LEAVE_DECIDED` "bị từ chối"; duyệt lại sau đó: 409 |
| LEA-11/12 | Admin duyệt với `newLeaderUserId` = M2: nhóm có đúng 1 Leader là M2 (role `GROUP_LEADER`); L1 **rời nhóm** và role về `STUDENT`; M2 nhận `LEADER_CHANGED`; M2 mời được thành viên |
| LEA-12 (b) | Chỉ đổi Leader bằng `PUT /groups/{id}/leader`: L1 **vẫn ở trong nhóm** (thành thành viên thường), yêu cầu rời nhóm vẫn `PENDING` |
| LEA-13 | Token sai hoặc hết hạn: 401, không tạo yêu cầu |
| LEA-14 | S1 hoặc L2 (không thuộc G1) gửi: 403, không tạo yêu cầu, Admin không nhận thông báo mới |

Quy ước của BE: **duyệt** yêu cầu rời nhóm của Leader nghĩa là L1 rời nhóm. Leader mới là người được chỉ định trong
`newLeaderUserId`; nếu không chỉ định thì là thành viên vào nhóm sớm nhất. **Chỉ đổi Leader** thì không xóa L1 khỏi nhóm.

## 5. Hồi quy: thành viên thường rời nhóm

| ID | Kiểm tra |
|---|---|
| REG-01..03 | M1 gửi `{"reason":"..."}`: `leaderRequest=false`; L1 nhận `LEAVE_REQUESTED`, Admin không nhận; L1 duyệt thì M1 rời nhóm, sĩ số từ 3 xuống 2 |
| REG-01 (b) | Thành viên gửi không kèm lý do vẫn được (giữ nguyên hợp đồng cũ) |
| REG-04 | L1 từ chối: M2 vẫn ở trong nhóm, sĩ số giữ 3 |
| REG-05a | Nhóm đã khóa: thành viên gửi thì nhận 409 "locked"; L1 duyệt yêu cầu đang chờ thì nhận 409; Admin vẫn duyệt được |
| REG-05b | Hết hạn lập nhóm: thành viên gửi thì nhận 409 "closed"; yêu cầu của Leader vẫn gửi được cho Admin |

## Lỗi BE phát hiện trong quá trình test

- **Thông báo bị trễ hoặc mất ngẫu nhiên** (`DomainEventOutbox`, `EmailOutbox`): `nextAttemptAt = Instant.now()` có độ
  chính xác tới 100ns, nhưng DB làm tròn **lên** tới micro giây. Vì vậy lần gửi ngay sau commit đôi khi thấy sự kiện
  "chưa tới hạn" và bỏ qua. Thông báo chỉ được gửi ở lần quét sau: khoảng 5 giây trên production, 1 giờ trong test.
  Đã sửa bằng cách cắt `nextAttemptAt` xuống micro giây ngay khi tạo.
- **Mojibake trong test**: MockMvc đọc JSON (không khai báo charset) bằng ISO-8859-1. `WorkflowTestSupport.body()` giờ
  đọc bằng UTF-8. Đây chỉ là lỗi của test, API thật không bị ảnh hưởng.
