# Chỉnh sửa tài liệu — Đánh giá nhóm (Evaluation) theo code Backend

Tài liệu này liệt kê các mục trong **BRD**, **SRS** và **Project Report** cần sửa để khớp với code Backend hiện tại.
Mỗi mục gồm: nội dung cũ → nội dung mới → lý do (dẫn chứng code).

## 0. Hành vi thực tế của Backend (căn cứ để sửa)

| Hạng mục | Code BE | Nguồn |
|---|---|---|
| Số tiêu chí | **3**: `topicFitScore`, `productQualityScore`, `communicationScore` | `evaluation/EvaluationRecord.java` |
| Thang điểm | Số nguyên **0–100** mỗi tiêu chí (validate `@Min(0) @Max(100)` + DB `CHECK BETWEEN 0 AND 100`) | `evaluation/dto/EvaluationCreateRequest.java`, `db/migration/V3__sprint2_5_schema.sql` |
| Điểm tổng | `totalScore = TopicFit×0.4 + ProductQuality×0.4 + Communication×0.2` — **trọng số cố định (hard-coded)**, không cấu hình theo môn | `EvaluationRecord.weightedTotal()` |
| Nhận xét | **Một trường nhận xét chung** `feedbackNotes` (TEXT) cho cả bản đánh giá — không có nhận xét riêng từng tiêu chí | `EvaluationRecord.java`, `EvaluationCreateRequest.feedback` |
| Trạng thái | Enum `DRAFT / SUBMITTED / PUBLISHED`; khi tạo được lưu **PUBLISHED ngay** ("Save and Publish" một bước) | `EvaluationStatus.java`, `EvaluationService.create()` |
| Phân quyền | Chỉ `INSTRUCTOR` được tạo; `STUDENT`/`GROUP_LEADER` chỉ xem bản PUBLISHED; `ADMIN`/`INSTRUCTOR` xem tất cả | `EvaluationController`, `EvaluationService` |
| Audit | Ghi audit `CREATE` (kèm `groupId`, `totalScore`) | `EvaluationService.create()` |
| API | `POST /api/v1/groups/{groupId}/evaluations`, `GET /api/v1/groups/{groupId}/evaluations`, `GET /api/v1/evaluations/{id}` (không có sửa/xóa) | `EvaluationController` |

---

## 1. BRD — Tài liệu nghiệp vụ

### Section 6.4 – Nhóm yêu cầu Đánh giá & Cảnh báo (BR-EVAL)

#### BR-EVAL-05

- **Cũ:** 4 Tiêu chí đánh giá cốt lõi: Progress, Code Quality, Documentation, Communication.
- **Mới:** **3 Tiêu chí đánh giá cốt lõi:** Giảng viên đánh giá nhóm theo 3 tiêu chí: **Topic Fit** (Độ phù hợp đề tài), **Product Quality** (Chất lượng sản phẩm) và **Communication** (Giao tiếp & làm việc nhóm).
- **Lý do:** Entity `EvaluationRecord` chỉ có 3 cột điểm `topic_fit_score`, `product_quality_score`, `communication_score`; không có Progress, Code Quality, Documentation.

#### BR-EVAL-06

- **Cũ:** Mỗi tiêu chí được đánh giá theo mức độ hoàn thành… kèm nhận xét.
- **Mới:** Mỗi tiêu chí được chấm bằng **điểm số nguyên từ 0 đến 100**. Hệ thống tự động tính **điểm tổng có trọng số** theo tỉ lệ cố định **Topic Fit 40% – Product Quality 40% – Communication 20%**. Mỗi lần đánh giá có **một nhận xét chung (feedback)** cho cả nhóm.
- **Lý do:** BE nhận điểm số 0–100 (không phải "mức độ hoàn thành"); chỉ có một trường `feedbackNotes` chung, không có nhận xét riêng từng tiêu chí.

#### BR-EVAL-07

- **Cũ:** Toàn bộ kết quả đánh giá 4 tiêu chí…
- **Mới:** Toàn bộ kết quả đánh giá **3 tiêu chí** (điểm từng tiêu chí, điểm tổng có trọng số, nhận xét chung, người chấm và thời điểm đánh giá) được lưu trên hệ thống. Kết quả được **công bố cho nhóm ngay khi giảng viên lưu** (trạng thái PUBLISHED) và được ghi vào **nhật ký kiểm toán (audit trail)**. Sinh viên và trưởng nhóm chỉ xem được các bản đánh giá đã công bố.
- **Lý do:** `EvaluationService.create()` lưu với `status = PUBLISHED` và gọi `auditService.record(... CREATE ...)`; `listByGroup()`/`getById()` lọc chỉ PUBLISHED cho Student/Group Leader.

---

## 2. SRS — Software Requirements

### Section 5 – FR-008 Evaluate Groups on Three Dimensions

#### FR-008.1

- **Old:** Instructor scores Topic Fit, Product Quality and Communication, each from 0–100.
- **New:** Instructor scores Topic Fit, Product Quality and Communication, each as an **integer from 0–100**, together with **one overall feedback comment**.
- **Reason:** Đúng 3 tiêu chí và thang 0–100; bổ sung kiểu số nguyên và trường feedback chung (`EvaluationCreateRequest`).

#### FR-008.2

- **Old:** Proposed baseline weights are 40/40/20.
- **New:** The system computes the weighted total score using **fixed weights of 40/40/20** (Topic Fit 40%, Product Quality 40%, Communication 20%). Weights are not configurable in the current version.
- **Reason:** Trọng số được hard-code trong `EvaluationRecord.weightedTotal()` — không còn là "proposed".

#### FR-008.7

- **Old:** Store separate comments for each evaluation dimension.
- **New:** Store **a single overall feedback comment** per evaluation record. Separate per-dimension comments are not supported in the current version.
- **Reason:** Chỉ có một cột `feedback_notes` (TEXT) trong bảng `evaluation_records`.

### Section 2.3 – FE-08

- **Old:** Score Topic Fit, Product Quality and Communication.
- **New:** Score Topic Fit, Product Quality and Communication (0–100 each) with an overall feedback comment; the system computes the 40/40/20 weighted total and **publishes the evaluation to the group immediately on save**.
- **Reason:** `EvaluationService.create()` tính `totalScore` và đặt `status = PUBLISHED`.

### Section 7 – RULE-020

- **Old:** Proposed weights are Topic Fit 40%, Product Quality 40%, Communication 20%.
- **New:** Each evaluation dimension is scored as an integer from 0–100. The weighted total uses **fixed weights**: Topic Fit 40%, Product Quality 40%, Communication 20% (`total = 0.4 × TopicFit + 0.4 × ProductQuality + 0.2 × Communication`). Weights are not configurable per course in the current version.
- **Reason:** Công thức cố định trong `EvaluationRecord.weightedTotal()`.

---

## 3. Project Report

### Section II.2 – FE-11

- **Old:** Lecturer scores Topic Fit, Product Quality, and Communication…
- **New:** Lecturer scores Topic Fit, Product Quality, and Communication (integer 0–100 each) with one overall feedback comment. The system computes the weighted total (40/40/20) and publishes the evaluation to the group immediately.

### Section II.5.3 – BR11

- **Old:** Evaluation stores the three scoring criteria…
- **New:** Evaluation stores the three scoring criteria — Topic Fit, Product Quality, and Communication (integer 0–100 each) — the weighted total score computed with fixed weights 40% / 40% / 20%, and **one overall feedback note**. Only Lecturers can create evaluations; Students and Group Leaders can view only published evaluations. Every evaluation creation is recorded in the audit trail.

### Section II.5.2 – UC15 Evaluate Group

- **Old:** …Topic Fit, Product Quality, and Communication…
- **New:**

| Field | Content |
|---|---|
| Actor | Lecturer (Instructor) |
| Precondition | Lecturer is logged in; the student group exists. |
| Main flow | 1. Lecturer opens the group and selects "Evaluate".<br>2. Lecturer enters scores for **Topic Fit**, **Product Quality** and **Communication** (integer 0–100 each) and one overall feedback note.<br>3. Lecturer clicks "Save and Publish".<br>4. System validates each score is within 0–100.<br>5. System computes the weighted total: 0.4 × Topic Fit + 0.4 × Product Quality + 0.2 × Communication.<br>6. System saves the evaluation record with status **PUBLISHED** and the evaluation time.<br>7. System writes an audit log entry (CREATE). |
| Alternative / Exception | 4a. A score is outside 0–100 → system rejects the request with a validation error.<br>Group not found → system returns "not found". |
| Postcondition | The evaluation is visible to the group's Students and Group Leader. |
| API | `POST /api/v1/groups/{groupId}/evaluations` |

### Section IV.4.2 – Entity EVALUATION_RECORDS

- **Old:** …scores for Topic Fit, Product Quality, Communication…
- **New:** Stores the Lecturer's evaluation of a group: scores for Topic Fit, Product Quality and Communication (integer 0–100 each), the weighted total score (40/40/20), one overall feedback note, evaluation time and publication status.

| Attribute | Type | Constraint | Description |
|---|---|---|---|
| id | UUID | PK | Evaluation record ID |
| group_id | UUID | FK → student_groups.id, NOT NULL | Evaluated group |
| instructor_id | UUID | FK → users.id, NOT NULL | Lecturer who evaluated |
| topic_fit_score | INT | NOT NULL, 0–100 | Topic Fit score |
| product_quality_score | INT | NOT NULL, 0–100 | Product Quality score |
| communication_score | INT | NOT NULL, 0–100 | Communication score |
| total_score | DOUBLE PRECISION | NOT NULL | Weighted total = 0.4·TF + 0.4·PQ + 0.2·C |
| feedback_notes | TEXT | NULL | Overall feedback comment |
| evaluated_at | TIMESTAMP | NULL | Evaluation time |
| status | VARCHAR(20) | NOT NULL, ∈ {DRAFT, SUBMITTED, PUBLISHED}, default DRAFT | Publication status (created as PUBLISHED) |
| created_at | TIMESTAMP | NOT NULL | Created time |
| updated_at | TIMESTAMP | NOT NULL | Last updated time |

---

## 4. Tóm tắt thay đổi

| Tài liệu | Mục | Loại sửa |
|---|---|---|
| BRD | BR-EVAL-05 | 4 tiêu chí → 3 tiêu chí (Topic Fit, Product Quality, Communication) |
| BRD | BR-EVAL-06 | "mức độ hoàn thành" → điểm 0–100; nhận xét riêng → một nhận xét chung; thêm trọng số 40/40/20 |
| BRD | BR-EVAL-07 | 4 → 3 tiêu chí; thêm publish ngay + audit + quyền xem |
| SRS | FR-008.1 | Bổ sung số nguyên + feedback chung |
| SRS | FR-008.2 | "Proposed" → trọng số cố định |
| SRS | FR-008.7 | Nhận xét riêng từng tiêu chí → một nhận xét chung |
| SRS | FE-08 | Bổ sung thang điểm, feedback, publish ngay |
| SRS | RULE-020 | "Proposed" → cố định, thêm công thức |
| Report | FE-11 | Bổ sung thang điểm, feedback, trọng số, publish ngay |
| Report | BR11 | Bổ sung điểm tổng, feedback chung, quyền xem, audit |
| Report | UC15 | Viết lại luồng theo `EvaluationService.create()` |
| Report | EVALUATION_RECORDS | Liệt kê đầy đủ thuộc tính theo migration V3 |
