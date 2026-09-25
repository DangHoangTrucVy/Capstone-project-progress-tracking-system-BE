# Event Storming Canvas
## Student Schedule and Guidance Management System

| Item | Value |
|---|---|
| Author | Group 2 |
| Version | 1.0 |
| Status | Draft for review |
| Last updated | 2026-09-24 |
| Previous documents | `01-BRD.md` (RULE), `02-PRD.md` (FR, API numbers) |
| Next document | `04-ADD.md` |

Legend: events are past-tense facts. Status: ✅ happens in the current code · 🟡 partial · ⚪ planned.
Hotspots (🔥) are open questions or known gaps that must be resolved before or during implementation.

### Bounded contexts
| Context | Flows | Aggregates |
|---|---|---|
| Identity & Access | Flow B | AGG-01 User |
| Group Management | Flow B | AGG-02 StudentGroup |
| Topic Knowledge | Flow C | AGG-03 Topic, AGG-04 QuestionBankItem |
| Scheduling | Flow A | AGG-05 ScheduleSlot, AGG-06 Booking |
| Meeting Support | Flow D, Flow E | AGG-07 ArtifactSubmission, AGG-08 MeetingSession, AGG-09 RequirementLog, AGG-10 MeetingMinute |
| Evaluation & Reporting | Flow F | AGG-11 EvaluationRecord, Report read model |
| Audit (cross-cutting) | all | AGG-12 SystemAuditTrail |
| Notification (planned) | all | Notification (planned) |

---

## 1. Domain Events

### Flow A — Assessment scheduling (FR-001, FR-002, FR-003)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-01 | **SlotPublished** | CMD-01 Publish slot | Slot stored `AVAILABLE`, `bookedCount = 0`, visible in slot list. | 🔥 Admin "department-wide" slots have no separate concept (RULE-003). | ✅ |
| DE-02 | **SlotOverlapRejected** | CMD-01 when the window overlaps another non-cancelled slot of the same instructor | 409 returned; nothing stored. | 🔥 Check against the school exam calendar (DEP-006)? | ✅ |
| DE-03 | **SlotSeriesGenerated** | CMD-02 Generate slots from a time window | N × DE-01. | Split rule when the window is not a multiple of the duration. | ⚪ |
| DE-04 | **SlotUpdated** | CMD-03 Edit slot | Affected groups notified (POL-17). | 🔥 What if capacity is reduced below `bookedCount`? | ⚪ |
| DE-05 | **SlotCancelled** | CMD-04 Cancel slot | Bookings cancelled, groups notified, priority rebooking (POL-17). | 🔥 How is "priority to rebook" enforced? | ⚪ |
| DE-06 | **SlotBooked** | CMD-05 Book slot | Booking `CONFIRMED`; `bookedCount + 1`; audit CREATE; confirmation (POL-15). | 🔥 Leader's membership in the group is not checked (FR-002.4). | ✅ (notification ⚪) |
| DE-07 | **BookingRejected** | CMD-05 when group already has a `CONFIRMED` booking (400) or slot is full (409) | Leader sees reason; next slot suggested (FR-002.8 ⚪). | 🔥 "Assessment round" not defined (OQ-003). | ✅ |
| DE-08 | **SlotFilled** | DE-06 when `bookedCount = capacityGroups` | Slot status `FULL`; hidden from available list. | — | ✅ |
| DE-09 | **BookingCancelled** | CMD-06 Cancel booking (reason required) | Booking `CANCELLED`, `cancelledAt` set, `bookedCount − 1`, audit CANCEL, notices (POL-17). | 🔥 Any leader/instructor can cancel any booking (FR-002.7). | ✅ |
| DE-10 | **LateCancellationRejected** | CMD-06 less than 2 h before start | 400 returned. | — | ✅ |
| DE-11 | **SlotReopened** | DE-09 on a `FULL` slot | Slot back to `AVAILABLE`. | — | ✅ |
| DE-12 | **BookingAttended** | CMD-07 / DE-40 session concluded | Booking `ATTENDED`; slot `COMPLETED` when all its bookings are closed. | 🔥 `ATTENDED`, `IN_SESSION`, `COMPLETED` states exist but are never set. | ⚪ |
| DE-13 | **BookingMarkedNoShow** | CMD-07 or scheduler after slot end with no session | Booking `NO_SHOW`; counted in reports. | 🔥 Consequence for the group? | ⚪ |
| DE-14 | **BookingConfirmationSent** | POL-15 after DE-06 | E-mail + in-app to members and instructor. | DEP-001 not integrated. | ⚪ |
| DE-15 | **SessionReminderSent** | POL-16, 24 h and 2 h before start | E-mail + in-app. | — | ⚪ |
| DE-16 | **InstructorSlotReminderSent** | POL-18, 3 days before a week with no published slots | E-mail to instructor. | — | ⚪ |

### Flow B — Identity and group formation (FR-010, FR-011)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-17 | **StudentRegistered** | CMD-10 Register | `User` with role `STUDENT`, `ACTIVE`; JWT issued. | — | ✅ |
| DE-18 | **RegistrationRejected** | CMD-10 with non-school domain or existing e-mail | 400 / 409. | — | ✅ |
| DE-19 | **UserLoggedIn** | CMD-11 Log in | Access token (60 min) issued. | 🔥 Refresh token configured but not issued (FR-010.3). | ✅ |
| DE-20 | **UserCreated** | CMD-12 Admin creates user | Account with chosen role. | Bulk import not built (OQ-002). | ✅ |
| DE-21 | **UserUpdated** | CMD-13 Admin updates name/avatar/status | Suspended/inactive users cannot log in. | Not audited. | ✅ |
| DE-22 | **GroupCreated** | CMD-14 Create group | Group `FORMED`; if creator is a student → DE-23. | Not audited. | ✅ |
| DE-23 | **StudentPromotedToLeader** | DE-22 by a student, or CMD-16 with `isLeader = true` | User role → `GROUP_LEADER`; member row with `isLeader = true`. | 🔥 Role is global, not per group. | ✅ |
| DE-24 | **StudentJoinedGroup** | CMD-15 Join group | Active member added. | — | ✅ |
| DE-25 | **MemberAdded** | CMD-16 Add member | Active member added. | 🔥 A leader of another group can add members (ownership). | ✅ |
| DE-26 | **MembershipRejected** | CMD-14/15/16 when group has 5 active members, student already in a group, or a second leader | 409. | — | ✅ |
| DE-27 | **MemberRemoved** | CMD-17 Remove member | Member `REMOVED`; if leader → role back to `STUDENT` (DE-28). | Group left without leader. | ✅ |
| DE-28 | **LeaderDemoted** | DE-27 on the leader | Role `STUDENT`. | — | ✅ |
| DE-29 | **GroupUpdated** (topic/supervisor assigned, status changed) | CMD-18 Update group | Supervisor may now run meetings/evaluate. | Evaluation does not check supervisor (FR-008.6). | ✅ |

### Flow C — Topic knowledge (FR-005)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-30 | **TopicCreated** | CMD-19 | Topic `DRAFT`, unique code. | — | ✅ |
| DE-31 | **TopicUpdated** (published/archived) | CMD-20 | Available / hidden for new groups. | Archiving a topic still used by groups. | ✅ |
| DE-32 | **QuestionAdded** | CMD-21 | Question `DRAFT` under the topic, with category. | 🔥 No way to move a question to `ACTIVE` (FR-005.5). | ✅ |
| DE-33 | **QuestionActivated / QuestionDeprecated** | CMD-22 | Visible / hidden in reviews. | — | ⚪ |
| DE-34 | **MaterialAttached** | CMD-23 | Reference material linked to topic. | Needs file storage (DEP-003). | ⚪ |

### Flow D — Artifact submission (FR-004)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-35 | **ArtifactSubmitted** | CMD-24 | `SUBMITTED`, version 1 or next version; audit CREATE. | 🔥 Client supplies `fileUrl`; no upload/size check. | ✅ |
| DE-36 | **ArtifactSuperseded** | DE-35 with the same title in the same group | Previous version `SUPERCEDED`. | — | ✅ |
| DE-37 | **ArtifactAccepted** | CMD-25 | `ACCEPTED`; audit APPROVE. | Accepting an old version? Only `SUBMITTED` allowed. | ✅ |
| DE-56 | **ArtifactSubmissionLocked** | POL-19, 2 h before the linked session | Further submissions for that session refused. | — | ⚪ |

### Flow E — Meeting, requirements and minutes (FR-006, FR-007)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-38 | **MeetingSessionOpened** | CMD-26 (from a `CONFIRMED` booking) | Session `SCHEDULED`; audit CREATE. | Only one session per booking. | ✅ |
| DE-39 | **MeetingStarted** | CMD-27 | Session `IN_PROGRESS`, `startedAt`. | — | ✅ |
| DE-40 | **MeetingConcluded** | CMD-28 (optionally with raw notes) | Session `CONCLUDED`, `endedAt`; counted as "session held" in reports. | 🔥 Should set booking `ATTENDED` (DE-12). | ✅ |
| DE-41 | **AttendanceRecorded** | CMD-29 | Per-member presence stored; real attendance rate. | 🔥 No attendance data today; report uses a proxy. | ⚪ |
| DE-42 | **RequirementLogged** | CMD-30 | Requirement `OPEN` with priority, linked to session and group; audit CREATE. | 🔥 No human-readable `REQ-xxx` ID; assignee/due date not mandatory. | ✅ |
| DE-43 | **RequirementStatusChanged** | CMD-31 | `OPEN → IN_PROGRESS → RESOLVED → CLOSED`; audit UPDATE. | 🔥 Any status can jump to any other; who may close? | ✅ |
| DE-44 | **RequirementAssigned** | CMD-31 with assignee | Responsible person set. | — | ✅ |
| DE-45 | **MinutesDrafted** | CMD-32 (notes or saved raw notes) | Minute `DRAFT` with generated content (Objectives, Discussion, New Requirements, Conclusion); audit CREATE/UPDATE. | 🔥 Template, not AI (DEP-004); no attendees/action items. | ✅ |
| DE-46 | **MinutesGenerationRejected** | CMD-32 with empty notes, or after submission | 400. | — | ✅ |
| DE-47 | **MinutesSubmittedByLeader** | CMD-33 (leader signs) | `UNDER_REVIEW`, `finalContent`, `studentSignedAt`; audit SIGN; instructor notified (⚪). | — | ✅ |
| DE-48 | **MinutesApproved** | CMD-34 decision APPROVE | `APPROVED`, `instructorSignedAt`, locked; audit APPROVE; statistics refresh (POL-21). | — | ✅ |
| DE-49 | **MinutesRejected** | CMD-34 decision REJECT with comments | `REJECTED` (needs revision); leader may resubmit; audit REJECT. | Comments stored only in audit details. | ✅ |
| DE-50 | **MinutesSignOffOverdue** | POL-20, 48 h after `MeetingConcluded` without approval | Both parties reminded / flagged. | — | ⚪ |

### Flow F — Evaluation and reporting (FR-008, FR-009)
| ID | Domain event | Trigger | Downstream effects | Hotspot | Status |
|---|---|---|---|---|---|
| DE-51 | **GroupEvaluated** | CMD-35 | Record `PUBLISHED` with 3 scores and weighted total (40/40/20); audit CREATE. | 🔥 Weights unconfirmed (A-004); any instructor can evaluate any group. | ✅ |
| DE-52 | **EvaluationResultNotified** | POL-22 after DE-51 | Group members notified. | — | ⚪ |
| DE-53 | **ReportSummaryProduced** | CMD-36 (semester, optional ISO week) | Sessions held, attendance rate, open/closed requirements. | 🔥 Weeks computed in UTC, not UTC+7. | ✅ |
| DE-54 | **GroupFlaggedAtRisk** | POL-24 when thresholds are exceeded | Supervisor / Dept Head alerted. | 🔥 Thresholds undefined (OQ-006). | ⚪ |
| DE-55 | **ReportExported** | CMD-37 | CSV/Excel file. | — | ⚪ |

---

## 2. Actors and Commands

| ID | Actor | Command | API | Aggregate | Guards (reject when…) | Resulting events | Status |
|---|---|---|---|---|---|---|---|
| CMD-01 | Instructor, Admin | Publish slot | API-001 | ScheduleSlot | end ≤ start; start in the past; duration < 5; capacity < 1; overlap | DE-01 / DE-02 | ✅ |
| CMD-02 | Instructor, Admin | Generate slot series | API-001 (extend) | ScheduleSlot | any generated slot overlaps | DE-03 | ⚪ |
| CMD-03 | Instructor, Admin | Edit slot | API-012 | ScheduleSlot | not owner; capacity < bookedCount | DE-04 | ⚪ |
| CMD-04 | Instructor, Admin | Cancel slot | API-013 | ScheduleSlot, Booking | not owner | DE-05, DE-09 | ⚪ |
| CMD-05 | Group Leader | Book slot | API-003 | Booking, ScheduleSlot | group already has a `CONFIRMED` booking; slot full; not leader of the group (⚪) | DE-06, DE-07, DE-08 | ✅ |
| CMD-06 | Group Leader, Instructor, Admin | Cancel booking | API-004 | Booking, ScheduleSlot | not `CONFIRMED`; < 2 h before start; not owner (⚪) | DE-09, DE-10, DE-11 | ✅ |
| CMD-07 | Instructor | Mark attended / no-show | API-015 | Booking | slot not started | DE-12, DE-13 | ⚪ |
| CMD-08 | All | Export booking to calendar | API-016 | Booking | — | — | ⚪ |
| CMD-10 | Public | Register | API-042 | User | domain not allowed; e-mail exists; password < 8 | DE-17, DE-18 | ✅ |
| CMD-11 | Public | Log in | API-043 | User | wrong credentials; user not active | DE-19 | ✅ |
| CMD-12 | Admin | Create user | API-046 | User | e-mail exists | DE-20 | ✅ |
| CMD-13 | Admin | Update user (name, avatar, status) | API-049 | User | — | DE-21 | ✅ |
| CMD-14 | Admin, Instructor, Student | Create group | API-052 | StudentGroup | code exists; student already in a group; supervisor not Instructor/Admin | DE-22, DE-23, DE-26 | ✅ |
| CMD-15 | Student | Join group | API-056 | StudentGroup | already in a group; group full | DE-24, DE-26 | ✅ |
| CMD-16 | Admin, Instructor, Group Leader | Add member | API-057 | StudentGroup | not Student/Leader account; already member; full; second leader | DE-25, DE-23, DE-26 | ✅ |
| CMD-17 | Admin, Instructor, Group Leader | Remove member | API-058 | StudentGroup | member not in group | DE-27, DE-28 | ✅ |
| CMD-18 | Admin, Instructor | Update group (topic, supervisor, status) | API-055 | StudentGroup | supervisor not Instructor/Admin | DE-29 | ✅ |
| CMD-19 | Admin | Create topic | API-021 | Topic | code exists | DE-30 | ✅ |
| CMD-20 | Admin | Update topic | API-022 | Topic | — | DE-31 | ✅ |
| CMD-21 | Admin | Add question | API-025 | QuestionBankItem | topic not found | DE-32 | ✅ |
| CMD-22 | Admin | Activate / deprecate question | API-026 | QuestionBankItem | — | DE-33 | ⚪ |
| CMD-23 | Admin | Attach material | API-027 | Topic | — | DE-34 | ⚪ |
| CMD-24 | Student, Group Leader | Submit artifact | API-005 | ArtifactSubmission | not an active member; session not found; locked (⚪) | DE-35, DE-36 | ✅ |
| CMD-25 | Instructor, Admin | Accept artifact | API-020 | ArtifactSubmission | not `SUBMITTED` | DE-37 | ✅ |
| CMD-26 | Group Leader, Instructor | Open meeting session | API-030 | MeetingSession | booking not `CONFIRMED`; session exists; not a member | DE-38 | ✅ |
| CMD-27 | Group Leader, Instructor | Start meeting | API-032 | MeetingSession | not `SCHEDULED` | DE-39 | ✅ |
| CMD-28 | Group Leader, Instructor | End meeting (raw notes) | API-033 | MeetingSession | not `IN_PROGRESS` | DE-40 | ✅ |
| CMD-29 | Instructor | Record attendance | API-034 | MeetingSession | — | DE-41 | ⚪ |
| CMD-30 | Group Leader, Instructor | Log requirement | API-007 | RequirementLog | not a member; missing title/priority | DE-42 | ✅ |
| CMD-31 | Group Leader, Instructor | Update requirement (status, assignee) | API-029 | RequirementLog | not a member | DE-43, DE-44 | ✅ |
| CMD-32 | Group Leader, Instructor | Generate minutes | API-008 | MeetingMinute | empty notes; already submitted | DE-45, DE-46 | ✅ |
| CMD-33 | Group Leader | Submit (sign) minutes | API-009 | MeetingMinute | status not `DRAFT`/`REJECTED`; not a member | DE-47 | ✅ |
| CMD-34 | Instructor, Admin | Approve / reject minutes | API-009 | MeetingMinute | status not `UNDER_REVIEW` | DE-48, DE-49 | ✅ |
| CMD-35 | Instructor | Evaluate group | API-010 | EvaluationRecord | score outside 0–100; not supervisor (⚪) | DE-51 | ✅ |
| CMD-36 | Admin (Dept Head ⚪) | View report summary | API-011 | Report read model | — | DE-53 | ✅ |
| CMD-37 | Admin | Export report | API-041 | Report read model | — | DE-55 | ⚪ |
| CMD-38 | System scheduler | Send reminders / check deadlines | job | Booking, MeetingMinute | — | DE-15, DE-16, DE-50, DE-56, DE-13 | ⚪ |

---

## 3. Policies

"When [event], then [command / reaction]".

| ID | Policy | Triggered by | Reaction | Rule | Where enforced | Status |
|---|---|---|---|---|---|---|
| POL-01 | When a slot is published, then reject it if it overlaps a non-cancelled slot of the same instructor. | CMD-01 | DE-02 | RULE-001 | `ScheduleSlotService` | ✅ |
| POL-02 | When a booking is requested, then lock the slot row and re-check capacity before saving. | CMD-05 | DE-06 / DE-07 | RULE-006 | `BookingService` + `SELECT … FOR UPDATE` + DB check `booked_count ≤ capacity_groups` | ✅ |
| POL-03 | When a group that already has a `CONFIRMED` booking books again, then reject. | CMD-05 | DE-07 | RULE-004 | `BookingService` | 🟡 per group, not per round |
| POL-04 | When booked groups reach capacity, then mark the slot `FULL`. | DE-06 | DE-08 | RULE-006 | `BookingService` | ✅ |
| POL-05 | When a booking on a `FULL` slot is cancelled, then reopen the slot. | DE-09 | DE-11 | RULE-006 | `BookingService` | ✅ |
| POL-06 | When a cancellation is requested less than 2 h before start, then reject. | CMD-06 | DE-10 | RULE-007 | `BookingService` | ✅ |
| POL-07 | When a student creates a group, then add them as leader and promote their role. | DE-22 | DE-23 | RULE-012 | `StudentGroupService` | ✅ |
| POL-08 | When the leader is removed, then demote them to Student. | DE-27 | DE-28 | RULE-012 | `StudentGroupService` | ✅ |
| POL-09 | When a member is added and the group already has 5 active members, a leader, or the student is in another group, then reject. | CMD-14/15/16 | DE-26 | RULE-011 | `StudentGroupService` | ✅ |
| POL-10 | When an artifact with an existing title is submitted, then supersede the previous version. | DE-35 | DE-36 | RULE-015 | `ArtifactSubmissionService` | ✅ |
| POL-11 | When a meeting session is opened, then require a `CONFIRMED` booking with no existing session. | CMD-26 | DE-38 | RULE-016 | `MeetingSessionService` + unique `booking_id` | ✅ |
| POL-12 | When minutes are submitted, then move to `UNDER_REVIEW` and block regeneration. | DE-47 | — | RULE-018/019 | `MeetingMinuteService` | ✅ |
| POL-13 | When minutes are rejected, then allow the leader to edit and resubmit. | DE-49 | CMD-33 | RULE-018 | `MeetingMinuteService` | ✅ |
| POL-14 | When a group is evaluated, then compute the 40/40/20 weighted total and publish. | CMD-35 | DE-51 | RULE-020 | `EvaluationService`, `EvaluationRecord.weightedTotal` | ✅ |
| POL-15 | When a slot is booked, then send a confirmation to members and instructor. | DE-06 | DE-14 | FR-003.1 | Notification (planned) | ⚪ |
| POL-16 | When a session is 24 h and 2 h away, then send reminders. | scheduler | DE-15 | RULE-008 | Scheduler (planned) | ⚪ |
| POL-17 | When a slot is changed or cancelled, then cancel/notify affected bookings and give them rebooking priority. | DE-04, DE-05 | DE-09, notices | RULE-010 | Planned | ⚪ |
| POL-18 | When an instructor has no slots for next week 3 days before it starts, then remind them. | scheduler | DE-16 | RULE-009 | Planned | ⚪ |
| POL-19 | When a session is 2 h away, then lock artifact submissions for it. | scheduler / CMD-24 | DE-56 | RULE-014 | Planned | ⚪ |
| POL-20 | When 48 h pass after a concluded meeting without approved minutes, then flag overdue. | scheduler | DE-50 | RULE-019 | Planned | ⚪ |
| POL-21 | When minutes are approved, then statistics reflect the session immediately. | DE-48 | DE-53 | RULE-022 | Report computed on request | 🟡 Report does not use minutes status |
| POL-22 | When an evaluation is published, then notify the group. | DE-51 | DE-52 | — | Planned | ⚪ |
| POL-23 | When a slot ends and its booking has no concluded session, then mark `NO_SHOW`; when a session concludes, mark `ATTENDED`. | scheduler, DE-40 | DE-12, DE-13 | — | Planned | ⚪ |
| POL-24 | When a group exceeds risk thresholds, then flag it. | DE-13, DE-42, DE-53 | DE-54 | OQ-006 | Planned | ⚪ |
| POL-25 | When a Student/Group Leader acts on a group resource, then require active membership of that group. | CMD-24/26–33, CMD-05/06/16/17 | 403 | RULE-005, RULE-024 | Meeting, requirement, minutes, artifact services | 🟡 Missing for booking and member management |
| POL-26 | When any tracked state changes, then write an audit record in the same transaction. | all ✅ commands | audit row | RULE-023 | `AuditService` (`Propagation.MANDATORY`) | 🟡 Not for users, topics, groups |

---

## 4. Aggregates

| ID | Aggregate (root) | Lifecycle / states | Invariants | Commands | Events | Enforced by |
|---|---|---|---|---|---|---|
| AGG-01 | **User** | `ACTIVE → SUSPENDED → INACTIVE`; role `ADMIN / INSTRUCTOR / GROUP_LEADER / STUDENT` | Unique e-mail; self-registered users are `STUDENT` from an allowed domain; password ≥ 8 chars (BCrypt hash). | CMD-10…13 | DE-17…21 | DB unique + check constraints; `AuthService`, `UserService` |
| AGG-02 | **StudentGroup** (+ GroupMember) | Group `FORMED → ACTIVE → COMPLETED → ARCHIVED`; member `ACTIVE → REMOVED` | Unique group code; ≤ 5 active members; ≤ 1 active leader; a student in ≤ 1 active group; supervisor is Instructor/Admin; unique (group, user). | CMD-14…18 | DE-22…29 | `StudentGroupService`; DB unique `(group_id, user_id)` |
| AGG-03 | **Topic** | `DRAFT → PUBLISHED → ARCHIVED` | Unique topic code; only Admin modifies. | CMD-19, 20, 23 | DE-30, 31, 34 | `TopicService`; RBAC |
| AGG-04 | **QuestionBankItem** | `DRAFT → ACTIVE → DEPRECATED` | Belongs to one topic; only Admin creates. | CMD-21, 22 | DE-32, 33 | `QuestionBankService` |
| AGG-05 | **ScheduleSlot** | `AVAILABLE ⇄ FULL → IN_SESSION → COMPLETED`; any → `CANCELLED` | `end > start`; `0 ≤ bookedCount ≤ capacityGroups`; no overlap per instructor. | CMD-01…04 | DE-01…05, 08, 11 | DB checks; row lock; `ScheduleSlotService` |
| AGG-06 | **Booking** | `CONFIRMED → ATTENDED / NO_SHOW`; `CONFIRMED → CANCELLED` | One `CONFIRMED` booking per group (per round, target); cancel only when `CONFIRMED` and ≥ 2 h before start; reason required. | CMD-05…08 | DE-06, 07, 09, 10, 12, 13 | `BookingService` |
| AGG-07 | **ArtifactSubmission** | `SUBMITTED → SUPERCEDED` or `SUBMITTED → ACCEPTED` | Version increments per (group, title); only one non-superseded version per title; only members submit. | CMD-24, 25 | DE-35…37, 38a | `ArtifactSubmissionService` |
| AGG-08 | **MeetingSession** | `SCHEDULED → IN_PROGRESS → CONCLUDED` | Exactly one session per `CONFIRMED` booking; transitions in order only. | CMD-26…29 | DE-38…41 | DB unique `booking_id`; `MeetingSessionService` |
| AGG-09 | **RequirementLog** | `OPEN → IN_PROGRESS → RESOLVED → CLOSED` | Linked to a session and its group; title and priority required. | CMD-30, 31 | DE-42…44 | DB checks; `RequirementLogService` |
| AGG-10 | **MeetingMinute** | `DRAFT → UNDER_REVIEW → APPROVED`; `UNDER_REVIEW → REJECTED → UNDER_REVIEW` | One per session; regenerate only in `DRAFT`; leader signs from `DRAFT`/`REJECTED`; instructor decides only in `UNDER_REVIEW`; `APPROVED` is final. | CMD-32…34 | DE-45…50 | DB unique `session_id`; `MeetingMinuteService` |
| AGG-11 | **EvaluationRecord** | `DRAFT → SUBMITTED → PUBLISHED` (current flow creates `PUBLISHED` directly) | Each score 0–100; total = 0.4·TF + 0.4·PQ + 0.2·C; non-privileged users only see `PUBLISHED`. | CMD-35 | DE-51, 52 | DB checks; `EvaluationService` |
| AGG-12 | **SystemAuditTrail** | Append-only | Never updated or deleted; written in the same transaction as the change. | (internal) | — | `AuditService` |
| — | **Report** (read model, not an aggregate) | — | Derived from sessions, requirements (and later attendance, evaluations). | CMD-36, 37 | DE-53…55 | `ReportService` |
| — | **Notification** (planned) | `PENDING → SENT / FAILED` | Retries on failure. | POL-15…22 | DE-14…16, 52 | Planned |
