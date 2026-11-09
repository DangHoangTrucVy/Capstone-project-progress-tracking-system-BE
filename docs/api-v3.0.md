# API v3.0 — Học kỳ là trung tâm, Room thay cho nhóm tự tạo

Áp dụng yêu cầu **v3.0 (08/11/2026)**. Migration: `V23__requirements_v30_semester_rooms.sql`. Seed dev: `db/seed/V2003__dev_seed_v30.sql`.

## 1. Mô hình mới

| v2.9 | v3.0 |
|---|---|
| Học kỳ → môn CAP (`subjects`) → phạm vi ngành (`formation_scopes`, có phase/deadline riêng) → nhóm | **Học kỳ** giữ phase, deadline, rule → ngành trong học kỳ (`formation_scopes`, không còn phase) → **Room** |
| Phase theo ngành: `PREPARING / OPEN / LOCKED / FINALIZED` | Phase theo học kỳ: `OPENING` (Mở học kỳ) → `FORMING` (Lập nhóm) → `SUPPLEMENT` (Bổ sung) → `CLOSED` (Kết thúc ghép nhóm) |
| Sinh viên tự tạo nhóm, nộp đơn | Moderator tạo Room; sinh viên **tự do vào/ra**, người vào Room trống đầu tiên là trưởng nhóm |
| Mã nhóm có ngay khi tạo (`FA26-CAP-SE-001`) | Room chưa có mã; mã `<HK>-<Ngành>-<nnn>` (vd `SP26-SE-001`) cấp khi Moderator **duyệt chốt** hoặc khi kết thúc |
| Sĩ số 3–5 cố định | Rule của học kỳ: `maxGroupSize` (mặc định 5), `defaultMinRoomSize` (3, mỗi Room có `minSize` riêng), `maxPendingRequests` (3), `requestTtlMinutes` (48h) |
| Deadline riêng từng ngành, tự mở/khóa | Một deadline chung; mọi chuyển phase do Moderator bấm; chỉ việc **khóa Room tại deadline** là tự động |
| Nhóm hết người bị giải tán và xóa | Room về **trống**, được giữ lại; Room trống bị đóng khi kết thúc |
| Chốt một lần, mở lại được | Chốt đợt 1 (cuối Lập nhóm), chốt đợt 2 (cuối Bổ sung); **không mở lại** |

`Room` và `nhóm` là **cùng một bản ghi** (`student_groups`): duyệt chốt chỉ đổi trạng thái và cấp mã.

## 2. Endpoint đã bỏ

| Đã bỏ | Thay bằng |
|---|---|
| `GET/PUT /subjects/{id}`, `GET /semesters/{s}/subjects`, `GET /scopes/{id}`, `GET /semesters/{s}/scopes` | `GET /semesters/{s}`, `GET/PUT /semesters/{s}/rules`, `GET /semesters/{s}/majors` |
| `PUT /scopes/{id}/deadline`, `POST /scopes/{id}/extend` | `PUT /semesters/{s}/deadline` (đặt hoặc gia hạn) |
| `GET /scopes/{id}/finalize-check`, `POST /scopes/{id}/finalize`, `POST /scopes/{id}/reopen` | `GET /semesters/{s}/phase-check`, `POST /semesters/{s}/finalize-round1`, `POST /semesters/{s}/finalize-round2` (không mở lại) |
| `POST /subjects/{id}/groups` (sinh viên tạo nhóm), `POST /scopes/{id}/groups` | `POST /semesters/{s}/rooms` (Moderator tạo Room hàng loạt) |
| `POST /groups/{id}/applications` | `POST /rooms/{id}/join` (Room khóa → tự tạo yêu cầu) |
| `GET /semesters/{s}/groups`, `GET /subjects/{id}/open-groups` | `GET /semesters/{s}/rooms`, `GET /semesters/{s}/rooms/search` |
| `GET /subjects/{id}/candidates`, `/subjects/{id}/my-requests`, `/subjects/{id}/complaints` | `/semesters/{s}/candidates`, `/semesters/{s}/my-requests`, `/semesters/{s}/complaints` |
| `POST /moderation/dissolve` | `POST /semesters/{s}/rooms/disband` |
| Tham số `subjectId` của `/statistics`, `/export`, `/export-check` | Chỉ còn `majorId` |
| Mọi đường dẫn `/groups/{id}/...` | `/rooms/{id}/...` (cùng hậu tố) |

## 3. Học kỳ, rule, phase

| Method | Path | Ai | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/semesters?q&phase&moderatedByMe&sort&direction` | mọi vai trò (theo quyền xem) | `sort`: code, startDate, phase, studentCount, roomCount |
| POST | `/api/v1/semesters` | Admin | `{code, name, startDate, endDate, formationOpensAt?, deadline?}`; phase bắt đầu là `OPENING` |
| GET | `/api/v1/semesters/{s}` | | `phase`, `phaseLabel`, `beforeDeadline`, `timeline{…roomsLockedAt, round1FinalizedAt, round2FinalizedAt, closedAt}`, `rules`, `moderator`, `studentCount`, `roomCount`, `groupCount`, `majorCount` |
| PUT | `/api/v1/semesters/{s}` | Admin (tên, ngày), Admin/Moderator (`formationOpensAt`) | Đổi deadline phải dùng `/deadline` |
| PUT | `/api/v1/semesters/{s}/moderator` | Admin | `{moderatorId}` |
| GET / PUT | `/api/v1/semesters/{s}/rules` | xem: người có quyền; sửa: Moderator | `{maxGroupSize, defaultMinRoomSize, maxPendingRequests, requestTtlMinutes, applyMinToRooms}`. Ở `OPENING` sĩ số tối đa sửa tự do, từ `FORMING` chỉ được tăng; sĩ số tối thiểu chỉ sửa trước chốt đợt 1 |
| PUT | `/api/v1/semesters/{s}/deadline` | Moderator | `{deadline}`; chỉ trước chốt đợt 1; phải cách hiện tại ít nhất `minDeadlineNoticeMinutes`. Nếu Room đã khóa theo deadline thì **gia hạn** và mở lại Room |
| GET | `/api/v1/semesters/{s}/phase-check` | Moderator | `canStartForming / startBlockers`, `canFinalizeRound1 / round1Blockers`, `canSkipSupplement`, `canFinalizeRound2 / round2Blockers`, `warnings`, `roomsBelowMinimum`, `majorsWithoutRooms`, `pendingFinalizeRequests`, `openComplaints`, `studentsWithoutGroup` |
| POST | `/api/v1/semesters/{s}/start-formation` | Moderator | OPENING → FORMING; cần có sinh viên, deadline ở tương lai và mỗi ngành có sinh viên phải có ≥ 1 Room |
| POST | `/api/v1/semesters/{s}/finalize-round1` | Moderator | `{skipSupplement?}`. Sau deadline. Chặn khi: còn Room chưa chốt có thành viên nhưng dưới `minSize`, còn khiếu nại / yêu cầu rời, còn yêu cầu chốt chưa xử lý. FORMING → SUPPLEMENT (báo các nhóm còn chỗ). Với `skipSupplement=true` (chỉ khi không còn sinh viên lẻ) → CLOSED |
| POST | `/api/v1/semesters/{s}/finalize-round2` | Moderator | SUPPLEMENT → CLOSED, cùng tiêu chí như đợt 1 |

**Tại deadline** (scheduler `app.formation.tick-ms`): mọi Room chưa được duyệt bị khóa (`roomsLockedAt`), mọi yêu cầu/lời mời đang chờ bị hủy, yêu cầu rời đang chờ trưởng nhóm thành khiếu nại gửi Moderator. Room dưới sĩ số **không** bị giải tán tự động.

**Kết thúc ghép nhóm** (chốt đợt 2 hoặc bỏ qua Bổ sung): Room còn thành viên chưa có mã thì được cấp mã theo thứ tự Room; Room trống bị đóng (`CLOSED`, không còn trong danh sách); hủy yêu cầu, lời mời, đề nghị chuyển trưởng nhóm. Từ đó chỉ xem và xuất file.

## 4. Room

`RoomResponse`: `id, code (null khi chưa thành nhóm), name, roomNo, wantedSkills, semesterCode, semesterPhase, majorId, majorCode, majorName, size, minSize, maxSize, belowMinimum, status, statusLabel, full, locked, finalizeStatus (NONE|REQUESTED|APPROVED|RETURNED), finalizeRequestedAt, finalizeNote, finalized, finalizedAt, leaderId, leaderName, members[]`.

`status`: `EMPTY` (Trống) · `OPEN` (Đang ghép) · `FULL` (Đủ) · `LOCKED` (trưởng nhóm khóa) · `FINALIZE_REQUESTED` (Đã gửi chốt) · `DEADLINE_LOCKED` (Bị khóa theo deadline) · `FINALIZED` (Đã thành nhóm) · `CLOSED`.

| Method | Path | Ai | Ghi chú |
|---|---|---|---|
| GET | `/api/v1/semesters/{s}/rooms/plan` | Moderator | Mỗi ngành: `students`, `rooms`, `suggestedRooms = ceil(students / defaultMinRoomSize)`, `missingRooms` |
| POST | `/api/v1/semesters/{s}/rooms` | Moderator, trước chốt đợt 1 | `{majorId, count?, minSize?}` → 201 `RoomResponse[]`. Tên tự sinh `Room 01`, `Room 02`… nối tiếp; bỏ `count` = tạo số Room còn thiếu theo plan |
| GET | `/api/v1/semesters/{s}/rooms?majorId&status&belowMinimum&finalized&q&page&size` | Moderator: tất cả; sinh viên: Room cùng ngành | Phân trang (`size` ≤ 200). `belowMinimum=true` là bộ lọc "Room dưới sĩ số tối thiểu" |
| GET | `/api/v1/semesters/{s}/rooms/search?q&skill&available` | Sinh viên | Tìm Room cùng ngành theo tên / kỹ năng cần tuyển; `available=true` chỉ còn Room vào được |
| GET | `/api/v1/semesters/{s}/candidates?q&skill&majorId` | Trưởng nhóm (Moderator kèm `majorId`) | Sinh viên chưa có Room cùng ngành |
| GET | `/api/v1/rooms/{id}` | | |
| PUT | `/api/v1/rooms/{id}` | Trưởng nhóm: `name`, `wantedSkills` (trước deadline, chưa duyệt); Moderator: thêm `minSize` (trước chốt đợt 1) | |
| POST | `/api/v1/rooms/{id}/join` | Sinh viên, FORMING trước deadline | `{message?}` → `{joined, leader, request, room}`. Room trống → vào và làm trưởng nhóm; Room mở → vào ngay; Room đang khóa → tạo yêu cầu (`request`) chờ trưởng nhóm. Room đủ / đã duyệt / đang chờ duyệt → 409 |
| POST | `/api/v1/rooms/{id}/leave` | Thành viên | `{reason?}` → `{left, request}`. Trước deadline, Room không khóa: rời ngay (trưởng nhóm rời → người vào sớm nhất lên thay; người cuối rời → Room về trống). Room khóa: yêu cầu gửi trưởng nhóm. Sau deadline hoặc nhóm đã duyệt: yêu cầu gửi Moderator |
| POST | `/api/v1/rooms/{id}/lock` · `/unlock` | Trưởng nhóm, trước deadline | Mở khóa sẽ hủy các yêu cầu vào Room đang chờ |
| DELETE | `/api/v1/rooms/{id}/members/{userId}` | Trưởng nhóm, trước deadline, chưa duyệt | |
| POST | `/api/v1/rooms/{id}/finalize-request` | Trưởng nhóm, trước deadline, sĩ số ≥ `minSize` | **Báo chốt**. Trong lúc chờ, không ai vào/ra Room |
| DELETE | `/api/v1/rooms/{id}/finalize-request` | Trưởng nhóm | Rút yêu cầu chốt |
| POST | `/api/v1/rooms/{id}/finalize-decision` | Moderator | `{approve, note?}`. Duyệt → Room thành nhóm, cấp mã ngay theo thứ tự duyệt (riêng từng ngành, không dùng lại). Trả lại → `RETURNED` kèm `note`. Moderator cũng được duyệt Room chưa báo chốt |
| GET | `/api/v1/semesters/{s}/finalize-requests` | Moderator | Hàng chờ duyệt chốt |

## 5. Yêu cầu, lời mời, rời nhóm, chuyển trưởng nhóm

| Method | Path | Ghi chú |
|---|---|---|
| POST | `/api/v1/rooms/{id}/invitations` | Trưởng nhóm. `{studentId \| studentCode, message?}`. FORMING trước deadline: có hạn `requestTtlMinutes`. **SUPPLEMENT**: không hết hạn, nhóm đã duyệt vẫn mời được, chỉ nhóm còn chỗ; nhiều nhóm được mời cùng một sinh viên |
| GET | `/api/v1/rooms/{id}/requests` | Thành viên Room, Moderator |
| GET | `/api/v1/semesters/{s}/my-requests` | Sinh viên |
| GET | `/api/v1/requests/{id}` · POST `/accept` · `/reject` · `/withdraw` · PUT/DELETE `/like` | Như cũ. Nhận một lời mời thì các lời mời khác tự hủy; ở Bổ sung, Moderator được báo khi sinh viên vào nhóm |
| GET | `/api/v1/rooms/{id}/leave-requests` · `/api/v1/me/leave-requests` | |
| POST | `/api/v1/leave-requests/{id}/leader-decision` · `/complaint` · `/withdraw` · `/moderator-decision` | Như cũ |
| GET | `/api/v1/semesters/{s}/complaints?majorId` | Moderator |
| POST | `/api/v1/rooms/{id}/leader-transfers` | Như cũ; vẫn được chuyển sau khi chốt (Moderator được báo), không được khi đã CLOSED |

## 6. Moderator điều phối

Được làm ở mọi phase trừ `CLOSED`, kể cả Room đang khóa, không cần đơn hay sự đồng ý:

| Method | Path | Ghi chú |
|---|---|---|
| POST | `/api/v1/rooms/{id}/moderation/add` | `{userId}`. Vào Room trống thì làm trưởng nhóm. Nhóm đã duyệt chỉ nhận thêm người ở **SUPPLEMENT** |
| POST | `/api/v1/rooms/{id}/moderation/move` | `{userId}` chuyển từ Room hiện tại sang Room này |
| POST | `/api/v1/rooms/{id}/moderation/remove` | `{userId, reason}`; **bắt buộc lý do**; áp dụng cả nhóm đã duyệt |
| POST | `/api/v1/rooms/{id}/moderation/leader` | `{userId}` chỉ định trưởng nhóm |
| POST | `/api/v1/rooms/{id}/moderation/merge` | `{sourceRoomId}`; Room nguồn về trống |
| POST | `/api/v1/semesters/{s}/rooms/disband` | `{roomIds?, allBelowMinimum?, majorId?, reason?}` → `{rooms, students}`. Giải tán về **trống** (không xóa). Không áp dụng cho nhóm đã duyệt |

## 7. Danh sách, thống kê, xuất file

- `GET /semesters/{s}/students?majorId&hasGroup&eligibility&q&page` — mỗi dòng có thêm `majorId`, `roomId`, `roomName`, `groupCode`.
- `GET /semesters/{s}/majors` — các ngành của học kỳ kèm mã ngắn và số sinh viên.
- `GET /me/semesters` — mỗi học kỳ: `phase`, `phaseLabel`, `deadline`, `beforeDeadline`, `canJoinRooms`, `canAcceptInvites`, `enrollmentId`, `eligibility`, `room{id, code, name, size, minSize, maxSize, status, leader, finalized}`.
- `GET /semesters/{s}/statistics?majorId` — `total` và `majors[]`: `totalStudents, withGroup, withoutGroup, ineligible, roomsBySize, roomsByStatus, totalRooms, emptyRooms, roomsBelowMinimum, groups, pendingFinalizeRequests, openComplaints, pendingRequests, pendingInvites`.
- `GET /semesters/{s}/export-check?majorId`, `GET /semesters/{s}/export?majorId` — xuất được bất cứ lúc nào. Sheet nhóm: Room chưa có mã để trống cột Mã nhóm. Đầu sheet ghi giai đoạn hiện tại; file chính thức là file xuất ở `CLOSED`.

## 8. Thông báo mới

`ROOM_FINALIZE_REQUESTED`, `ROOM_FINALIZE_DECIDED`, `ROOM_CHANGED_BY_MODERATOR`, `FORMING_STARTED`, `SUPPLEMENT_OPENED`, `FORMATION_CLOSED`, `STUDENTS_NEED_GROUP`, `SUPPLEMENT_JOINED` (các loại cũ vẫn dùng: `SCOPE_LOCKED` = khóa theo deadline, `SCOPE_EXTENDED` = gia hạn, `DEADLINE_CHANGED`, `BECAME_LEADER`, `GROUP_DISSOLVED` = Room bị giải tán…).

## 9. Các mục ĐỀ XUẤT đang áp dụng (mục 15 của yêu cầu)

Rule mặc định 5 / 3 / 3 yêu cầu / 48 h; sĩ số tối đa chỉ tăng từ Lập nhóm · Trưởng nhóm đổi tên, đặt kỹ năng, kick trước khi chốt · Báo chốt cần đủ `minSize`, Moderator có thể trả lại · Start formation cần sinh viên, deadline và Room cho mỗi ngành · Bổ sung: nhận một lời mời thì hủy các lời mời khác, chỉ nhóm còn chỗ được mời · Gia hạn chỉ trước chốt đợt 1 · Excel để trống mã nhóm của Room chưa chốt · Sinh viên bị xếp sau deadline gửi yêu cầu chuyển/rời tới Moderator (qua `leave`) · Gỡ cờ không trả về Room cũ · Room trống bị đóng khi kết thúc · Chốt đợt 2 cùng tiêu chí đợt 1 · Nhập lại danh sách sau OPENING chỉ cập nhật thông tin liên hệ.

Khác biệt nhỏ so với đề xuất: hai sinh viên vào cùng một Room trống cùng lúc thì người được ghi nhận trước làm trưởng nhóm, **người sau vào luôn làm thành viên** (không chuyển thành đơn chờ, vì Room đang mở).
