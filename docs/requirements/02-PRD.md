# Product Requirements Document (PRD)
## Student Schedule and Guidance Management System

| Item | Value |
|---|---|
| Author | Group 2 |
| Version | 1.0 |
| Status | Draft for review |
| Last updated | 2026-09-24 |
| Previous document | `01-BRD.md` (BR, ST, CAP, RULE, A/C/OQ/DEP IDs) |
| Next document | `03-Event-Storming.md` |
| Sources of truth | `blueprint.md` v1.0 §6–§10, backend code |

Priority: MoSCoW (Must / Should / Could). Status: ✅ Implemented · 🟡 Partial · ⚪ Not implemented.
`FR-001`…`FR-010` and `NFR-001`…`NFR-006` keep their blueprint IDs. `FR-011`, `FR-012` and `NFR-007`+ are new and extend the blueprint.

---

## 1. Business Capabilities

| ID | Capability | Domain boundary / major business flow | Main actors | Entities | BRD link | FRs | Status |
|---|---|---|---|---|---|---|---|
| BC-01 | Assessment Scheduling | Slot → Booking. Publish availability, book with capacity control, cancel outside the late window. | Instructor, Admin, Group Leader | ScheduleSlot, Booking | BR-001, CAP-01 | FR-001, FR-002 | 🟡 |
| BC-02 | Notifications & Calendar | Domain event → e-mail / in-app message; calendar export. | System | (Notification – planned) | BR-001, CAP-02 | FR-003 | ⚪ |
| BC-03 | Identity & Access | Register / login / SSO → role → permission on each resource. | All, Admin | User | BR-002, CAP-03 | FR-010 | 🟡 |
| BC-04 | Group Management | Group formation → members and leader → topic and supervisor assignment. | Student, Group Leader, Instructor, Admin | StudentGroup, GroupMember | BR-002, CAP-04 | FR-011 | ✅ |
| BC-05 | Topic Knowledge | Topic → categorized questions and guidance → used when preparing reviews. | Admin, Instructor | Topic, QuestionBankItem | BR-002, CAP-05 | FR-005 | 🟡 |
| BC-06 | Artifact Submission | Group submits versioned deliverables per round → instructor accepts. | Group Member, Instructor | ArtifactSubmission | BR-002, CAP-06 | FR-004 | 🟡 |
| BC-07 | Meeting Support & Minutes | Booking → meeting session → requirements → draft minutes → two-party sign-off. | Group Leader, Instructor | MeetingSession, RequirementLog, MeetingMinute | BR-003, CAP-06 | FR-006, FR-007 | 🟡 |
| BC-08 | Group Evaluation | Instructor scores 3 dimensions → weighted total → published to the group. | Instructor | EvaluationRecord | BR-004, CAP-07 | FR-008 | 🟡 |
| BC-09 | Reporting & Analytics | Sessions, requirements, evaluations → weekly / semester statistics. | Admin, Dept Head | (read model) | BR-005, CAP-08 | FR-009 | 🟡 |
| BC-10 | Audit | Every state change → immutable audit record. | System | SystemAuditTrail | BR-003, BR-004, CAP-09 | FR-012 | 🟡 |

---

## 2. Functional Requirements

### FR-001 Manage assessment slots — *Must* · BR-001 · UC-001 · US-001
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-001.1 | Create a slot with start time, end time (after start, in the future), duration ≥ 5 min, capacity ≥ 1 group, location type (ONLINE/OFFLINE) and meeting URL or room. New slot is `AVAILABLE`. | Instructor, Admin | RULE-002, RULE-003 | API-001 | ✅ |
| FR-001.2 | Reject a slot that overlaps an existing non-cancelled slot of the same instructor (409). | System | RULE-001 | API-001 | ✅ |
| FR-001.3 | Generate consecutive slots from a time window and a per-slot duration (e.g. 08:00–10:00, 30 min → 4 slots of capacity 1). | Instructor, Admin | RULE-001 | API-001 (extend) | ⚪ |
| FR-001.4 | Preview generated slots and conflicts before publishing. | Instructor | RULE-001 | — | ⚪ |
| FR-001.5 | Edit a slot that has no bookings; changing a booked slot notifies the affected groups. | Instructor, Admin | RULE-010 | API-012 | ⚪ |
| FR-001.6 | Cancel a slot; its bookings are cancelled, groups are notified and get priority to rebook. | Instructor, Admin | RULE-010 | API-013 | ⚪ |
| FR-001.7 | List and filter slots by instructor, date range and status; view one slot with remaining capacity. | All roles | — | API-002, API-014 | ✅ |
| FR-001.8 | Admin creates department-wide assessment slots on behalf of instructors. | Admin | RULE-003 | API-001 | 🟡 Admin can only create slots under its own account |

**Acceptance (US-001):** Given an instructor on the slot page, when they enter 08:00–10:00, 30 min per slot, capacity 1 and click Create, then 4 slots are created, each `AVAILABLE` with capacity 1.

### FR-002 Book assessment slots — *Must* · BR-001 · UC-002 · US-002, US-003
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-002.1 | A Group Leader books an `AVAILABLE` slot for their group, with optional preparation notes; booking is `CONFIRMED`. | Group Leader | RULE-005 | API-003 | ✅ |
| FR-002.2 | Capacity is re-checked under a row lock; the last seat can never be taken twice (409 "Slot full"). | System | RULE-006 | API-003 | ✅ |
| FR-002.3 | A group can hold only one active booking per assessment round (400 "Already booked"). | System | RULE-004 | API-003 | 🟡 Enforced as one active booking at any time (OQ-003) |
| FR-002.4 | The acting Group Leader must be an active member of the group being booked. | System | RULE-005 | API-003 | ⚪ Ownership gap |
| FR-002.5 | The slot switches to `FULL` when capacity is reached and back to `AVAILABLE` after a cancellation. | System | RULE-006 | API-003/004 | ✅ |
| FR-002.6 | Cancel a `CONFIRMED` booking with a mandatory reason; refused within 2 h of the start (400 "Late cancellation"). | Group Leader, Instructor, Admin | RULE-007 | API-004 | ✅ |
| FR-002.7 | Only the group's own leader, the slot's instructor or an admin may cancel a booking. | System | RULE-005 | API-004 | ⚪ Ownership gap |
| FR-002.8 | When a slot fills during booking, suggest the next available slot. | System | UC-002 5b | API-003 | ⚪ |
| FR-002.9 | Instructor marks a booking `ATTENDED` or `NO_SHOW` after the session time. | Instructor | — | API-015 | ⚪ Statuses exist, no transition |
| FR-002.10 | Calendly-style grid of available days and slots. | Group Leader | D-001 | API-002 | ⚪ Frontend |

**Acceptance (US-003):** Given slot S-101 with 1 seat left, when groups A and B book in the same second, then exactly one succeeds, the other receives "Slot full", and S-101 becomes `FULL`.

### FR-003 Notifications and calendar — *Should* · BR-001
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-003.1 | Send booking confirmation (e-mail + in-app) to all group members and the instructor. | System | — | event | ⚪ |
| FR-003.2 | Send reminders 24 h and 2 h before the session. | System | RULE-008 | job | ⚪ |
| FR-003.3 | Notify on booking cancellation and on slot change/cancellation. | System | RULE-010 | event | ⚪ |
| FR-003.4 | Remind instructors without slots for next week, 3 days ahead. | System | RULE-009 | job | ⚪ |
| FR-003.5 | Notify on minutes submitted, approved/rejected, and evaluation published. | System | — | event | ⚪ |
| FR-003.6 | Export a booking as `.ics` and a Google Calendar link. | All | A-006 | API-016 | ⚪ |
| FR-003.7 | List and mark in-app notifications as read. | All | — | API-017 | ⚪ |

### FR-004 Submit progress artifacts — *Must* · BR-002
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-004.1 | An active group member submits an artifact (title, file URL, file type, optional session). | Student, Group Leader | RULE-024 | API-005 | ✅ |
| FR-004.2 | Same title → new version; previous version becomes `SUPERSEDED`. | System | RULE-015 | API-005 | ✅ |
| FR-004.3 | List a group's artifacts and view one artifact. | Group, Instructor, Admin | RULE-024 | API-018, API-019 | ✅ (visibility not restricted to own group for reads) |
| FR-004.4 | Instructor/Admin accepts a `SUBMITTED` artifact. | Instructor, Admin | RULE-015 | API-020 | ✅ |
| FR-004.5 | Upload the file itself (multipart) with size and type validation (413 / 400). | Student, Group Leader | — | API-005 | ⚪ Client supplies `fileUrl` |
| FR-004.6 | Lock submissions for a session 2 h before it starts. | System | RULE-014 | API-005 | ⚪ |

### FR-005 Topics, question bank and materials — *Should* · BR-002
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-005.1 | Create and update topics (unique code, title, description, category, status DRAFT/PUBLISHED/ARCHIVED). | Admin | — | API-021, API-022 | ✅ |
| FR-005.2 | List topics (filter by status) and view a topic. | All roles | — | API-023, API-024 | ✅ |
| FR-005.3 | Add questions to a topic (category, question text, guidance notes). | Admin | — | API-025 | ✅ |
| FR-005.4 | List a topic's questions, filtered by category. | All roles | — | API-006 | ✅ |
| FR-005.5 | Activate, edit and deprecate questions (DRAFT → ACTIVE → DEPRECATED). | Admin | — | API-026 | ⚪ Questions stay `DRAFT` |
| FR-005.6 | Attach reference materials to a topic. | Admin | — | API-027 | ⚪ |

### FR-006 Log new requirements in meetings — *Must* · BR-003 · UC-003 · US-004
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-006.1 | Log a requirement in a session with title, description and priority (HIGH/MEDIUM/LOW); starts `OPEN`, linked to session and group. | Group Leader, Instructor | RULE-017 | API-007 | ✅ |
| FR-006.2 | Only members of the group (or instructors/admins) can log or update its requirements. | System | RULE-024 | API-007, API-029 | ✅ |
| FR-006.3 | Update status (OPEN → IN_PROGRESS → RESOLVED → CLOSED) and assignee. | Group Leader, Instructor | — | API-029 | ✅ |
| FR-006.4 | Responsible person and due date are mandatory. | System | RULE-017 | API-007 | 🟡 Assignee optional, no due date |
| FR-006.5 | Each requirement gets a stable human-readable ID (e.g. `REQ-012`). | System | RULE-017 | API-007 | ⚪ UUID only |
| FR-006.6 | List requirements by session and by group. | Group, Instructor | — | API-028 | 🟡 By session only |

**Acceptance (US-004):** Given a running session, when a user saves a title, description and due date, then the requirement appears in the session's list with a stable ID such as `REQ-012` and status `OPEN`.

### FR-007 Meeting sessions and minutes — *Must* · BR-003 · UC-003 · US-005, US-006
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-007.1 | Open a meeting session from a `CONFIRMED` booking (one session per booking), status `SCHEDULED`. | Group Leader, Instructor | RULE-016 | API-030 | ✅ |
| FR-007.2 | Start (`IN_PROGRESS`) and end (`CONCLUDED`) the session; save raw notes on end. | Group Leader, Instructor | — | API-032, API-033 | ✅ |
| FR-007.3 | Generate draft minutes from notes with sections Objectives, Discussion, New Requirements, Conclusion; 400 on empty notes. | Group Leader, Instructor | RULE-018 | API-008 | ✅ Deterministic template |
| FR-007.4 | Draft minutes are produced by a text-summarization/AI service and include attendees and action items, in under 3 s. | System | A-003 | API-008 | 🟡 No AI; no attendee/action-item sections |
| FR-007.5 | Leader edits the final content and submits (`UNDER_REVIEW`, student signature time recorded). | Group Leader | RULE-018 | API-009 | ✅ |
| FR-007.6 | Instructor approves (`APPROVED`) or rejects with comments (`REJECTED` = needs revision); leader can resubmit after rejection. | Instructor, Admin | RULE-018 | API-009 | ✅ |
| FR-007.7 | Minutes not approved within 48 h are flagged to both parties. | System | RULE-019 | job | ⚪ |
| FR-007.8 | Approved minutes are read-only; minutes can no longer be regenerated once submitted. | System | RULE-019 | API-008/009 | ✅ |
| FR-007.9 | Upload a transcript text file as input for minutes. | Group Leader | A-003 | API-008 | ⚪ |
| FR-007.10 | Record attendance of each group member for the session. | Instructor | — | API-034 | ⚪ Needed for FR-009.1 |
| FR-007.11 | During the session, look up topic questions and record questions asked and answers. | Instructor | intent §4 | API-006, API-035 | 🟡 Look-up only |
| FR-007.12 | View a session and its minutes (generated and final content, signatures, status). | Group, Instructor, Admin | RULE-024 | API-031, API-060 | ✅ |

**Acceptance (US-006):** Given minutes in `UNDER_REVIEW`, when the instructor clicks Approve, then the status becomes `APPROVED`, editing is locked and the group is notified.

### FR-008 Evaluate groups on three dimensions — *Must* · BR-004 · UC-004 · US-007
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-008.1 | Score Topic Fit, Product Quality and Communication (each 0–100) with feedback. | Instructor | RULE-020 | API-010 | ✅ |
| FR-008.2 | Compute weighted total (40/40/20). | System | RULE-020 | API-010 | ✅ Weights hard-coded |
| FR-008.3 | Configure weights per course. | Admin | A-004 | API-036 | ⚪ |
| FR-008.4 | "Save and Publish" in one action; record becomes `PUBLISHED`. | Instructor | UC-004 | API-010 | ✅ |
| FR-008.5 | Students see only `PUBLISHED` evaluations of their own group; unpublished records are hidden (404). | System | RULE-021 | API-037, API-038 | ✅ (own-group restriction ⚪) |
| FR-008.6 | Only the group's supervisor can evaluate it. | System | RULE-021 | API-010 | ⚪ |
| FR-008.7 | Separate comment for each dimension. | Instructor | UC-004 step 3 | API-010 | 🟡 Single feedback field |
| FR-008.8 | Notify the group when results are published. | System | — | event | ⚪ |

### FR-009 Statistics and reports — *Should* · BR-005 · US-008
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-009.1 | Summary per semester and optional ISO week: sessions held, attendance rate, open and closed requirements. | Admin | RULE-022 | API-011 | ✅ (attendance = concluded / all sessions, proxy) |
| FR-009.2 | Dept Head can read reports. | Dept Head | ST-005 | API-011 | ⚪ No role |
| FR-009.3 | List groups without a booking in the selected week. | Admin, Dept Head | US-008 | API-039 | ⚪ |
| FR-009.4 | Week-by-week and semester trend charts of progress. | Admin, Dept Head | — | API-040 | ⚪ |
| FR-009.5 | Flag at-risk groups using thresholds. | System | OQ-006 | API-039 | ⚪ |
| FR-009.6 | Export reports and scores to CSV/Excel. | Admin | blueprint §2 | API-041 | ⚪ |
| FR-009.7 | Instructors see statistics for their own groups. | Instructor | — | API-011 | ⚪ Admin-only |

### FR-010 Users, authentication and RBAC — *Must* · BR-002
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-010.1 | Self-register with a school e-mail (default `@fpt.edu.vn`, configurable), password ≥ 8 chars; role `STUDENT`. | Public | RULE-013 | API-042 | ✅ |
| FR-010.2 | Log in and receive a JWT access token (60 min). | Public | — | API-043 | ✅ |
| FR-010.3 | Refresh token (7 days) to renew the session. | All | — | API-044 | 🟡 Configured, not issued |
| FR-010.4 | View own profile. | All | — | API-045 | ✅ |
| FR-010.5 | Admin creates users with a role, lists (filter by role), views and updates users (name, avatar, status ACTIVE/SUSPENDED/INACTIVE). | Admin | — | API-046…049 | ✅ |
| FR-010.10 | Admin changes a user's role. | Admin | — | API-049 | ⚪ Role not editable after creation |
| FR-010.6 | Log in with Google SSO restricted to the school domain. | All | A-005 | API-050 | ⚪ |
| FR-010.7 | Enforce four roles on every endpoint. | System | — | all | ✅ |
| FR-010.8 | Enforce resource ownership (own group, own slots, supervised groups). | System | RULE-005 | all | 🟡 Meetings/requirements/minutes/artifacts only |
| FR-010.9 | Bulk import users and groups from Excel/CSV. | Admin | OQ-002 | API-051 | ⚪ |

### FR-011 Manage student groups — *Must* · BR-002 *(new; not an explicit FR in blueprint)*
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-011.1 | Create a group with unique code and semester; topic and supervisor optional. A student creator becomes leader. | Admin, Instructor, Student | RULE-011, RULE-012 | API-052 | ✅ |
| FR-011.2 | Update topic, supervisor (must be Instructor/Admin) and status (FORMED/ACTIVE/COMPLETED/ARCHIVED). | Admin, Instructor | — | API-055 | ✅ |
| FR-011.3 | A student joins a group that has room, if not already in one. | Student | RULE-011 | API-056 | ✅ |
| FR-011.4 | Add a member (Student/Group Leader only), optionally as leader; max 5 active; one active leader. | Admin, Instructor, Group Leader | RULE-011 | API-057 | ✅ (leader of another group can add, ownership ⚪) |
| FR-011.5 | Remove a member; removing the leader demotes them to Student. | Admin, Instructor, Group Leader | RULE-012 | API-058 | ✅ |
| FR-011.6 | List groups (filter by supervisor, topic, or only groups with room) and view a group with members. | All roles | — | API-053, API-054 | ✅ |

### FR-012 Audit trail — *Must* · BR-003, BR-004 · NFR-006 *(new)*
| ID | Requirement | Actor | Rule | API | Status |
|---|---|---|---|---|---|
| FR-012.1 | Record every state change with entity, entity ID, action, user, time and details, in the same transaction as the change. | System | RULE-023 | internal | 🟡 Covers bookings, sessions, requirements, minutes, artifacts, evaluations; not users, topics, groups |
| FR-012.2 | Admin searches the audit trail by entity and time. | Admin | RULE-023 | API-059 | ⚪ |

---

## 3. Non-Functional Requirements

| ID | Category | Requirement | Measurement / acceptance | Priority | Status |
|---|---|---|---|---|---|
| NFR-001 | Performance | Slot look-up API ≤ 500 ms and booking ≤ 1.5 s at P95. | APM or k6 report at P95. | Must | ⚪ Not measured |
| NFR-002 | Concurrency | 0 over-bookings with ≥ 50 groups booking the last seat at the same time. | k6/JMeter load test against PostgreSQL; database row lock. | Must | 🟡 Row lock done; load test not run; unit test runs on H2 |
| NFR-003 | Availability | ≥ 99.5% uptime over the 15 teaching weeks, excluding announced maintenance. | Uptime monitor on a health endpoint. | Must | ⚪ No actuator/monitoring |
| NFR-004 | Security | HTTPS (TLS 1.3); AES-256 encryption at rest; BCrypt passwords; strict RBAC and ownership checks. | OWASP ZAP scan + pen-test checklist. | Must | 🟡 BCrypt + RBAC done; TLS/at-rest encryption at deployment; ownership partial |
| NFR-005 | Usability | Calendly-like UI; booking in ≤ 3 clicks; ≥ 95% task success. | Usability test with a student sample. | Should | ⚪ Frontend |
| NFR-006 | Auditability | 100% of cancellations, score changes and minutes changes logged with timestamp and user ID. | Query audit table; cross-check during appeals. | Must | 🟡 See FR-012 |
| NFR-007 | Minutes latency | Draft minutes returned in < 3 s. | API timing test. | Should | ✅ Template is instant (re-test when AI is added) |
| NFR-008 | Compatibility | Responsive on desktop and mobile; latest 2 versions of Chrome, Edge, Firefox, Safari. | Cross-browser checklist. | Must (C-002) | ⚪ Frontend |
| NFR-009 | Data retention | Semester data active 6 months, archived ≥ 1 year, then reviewed for purge. | Retention job / procedure documented. | Should | ⚪ |
| NFR-010 | Time handling | Store all timestamps in UTC; display in Asia/Ho_Chi_Minh (UTC+7). | Integration tests on reminders and week boundaries. | Must | 🟡 UTC storage done; weeks computed in UTC |
| NFR-011 | Recoverability | Daily database backup; RPO ≤ 24 h, RTO ≤ 4 h. | Restore drill once per semester. | Should | ⚪ |
| NFR-012 | Privacy | Restricted data (scores, confidential comments) never returned as raw entities; students see only their own group's data. | Code review + API tests per role. | Must | 🟡 DTOs used; own-group read restriction missing |
| NFR-013 | Maintainability & testing | Layered code per feature; integration tests for every main flow; concurrency test on real PostgreSQL. | CI runs `mvn test`; Testcontainers for locking tests. | Should | 🟡 12 integration tests on H2 |
| NFR-014 | Supportability | Supported framework versions with security patches. | Dependency check in CI. | Should | ⚪ Spring Boot 3.3.x is out of open-source support (see ADR-011) |
| NFR-015 | Localization | UI in Vietnamese and English. | UI review. | Could | ⚪ Frontend |
