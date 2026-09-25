# Business Requirements Document (BRD)
## Student Schedule and Guidance Management System (Capstone Project Progress Tracking System)

| Item | Value |
|---|---|
| Author | Group 2 |
| Version | 1.0 |
| Status | Draft for review |
| Last updated | 2026-09-24 |
| Sources of truth | `intent.md`, `blueprint.md` v1.0, backend code (Flyway V1–V4) |
| Next document | `02-PRD.md` |

### ID conventions (shared by all 4 documents)
| Prefix | Meaning | Defined in |
|---|---|---|
| `ST-xxx` | Stakeholder | This BRD §3 (same IDs as blueprint §3) |
| `BR-xxx` | Business requirement / objective | This BRD §2 (same IDs as blueprint §6) |
| `CAP-xx` | Business capability (scope item) | This BRD §4 |
| `RULE-xxx` | Business rule | This BRD §5 |
| `A-xxx`, `C-xxx`, `OQ-xxx`, `D-xxx`, `DEP-xxx` | Assumption, Constraint, Open question, Decision, Dependency | This BRD §6 |
| `FR-xxx`, `NFR-xxx`, `UC-xxx`, `US-xxx` | Functional / non-functional requirement, use case, user story | `02-PRD.md` (same IDs as blueprint) |
| `DE-xx`, `CMD-xx`, `POL-xx`, `AGG-xx` | Domain event, command, policy, aggregate | `03-Event-Storming.md` |
| `API-xxx`, `ADR-xxx`, `COMP-xx`, `TS-xx` | API contract, architecture decision, component, technology | `04-ADD.md` |

Implementation status used across documents: ✅ Implemented · 🟡 Partially implemented · ⚪ Not implemented (planned).

---

## 1. Problem Statement

### 1.1 Summary
The supervision, tracking and evaluation of capstone/thesis projects between supervisors and student groups is fragmented and manual. Meeting times are negotiated through chat and email, group information and progress artifacts are scattered across personal channels, topic question banks are not standardized, meeting outcomes and newly raised requirements are not recorded in a traceable way, and progress statistics and evaluations must be compiled by hand at the end of each week and semester.

### 1.2 Affected audience
| Stakeholder | How the problem affects them |
|---|---|
| ST-001 Student (Group Member) | Cannot see a reliable schedule, feedback, or the list of requirements agreed in meetings. |
| ST-002 Group Leader | Spends several rounds of messages to agree on a meeting time; writes minutes manually days later; relays information between the supervisor and the team. |
| ST-003 Instructor / Supervisor | Sends individual messages to arrange meetings; collects documents from many channels; prepares questions ad hoc; records requirements in personal notes; evaluates in private spreadsheets. |
| ST-004 Academic Admin | Has no central place to manage topics, question banks, slot policy, and user accounts. |
| ST-005 Academic Board / Dept Head | Must contact each supervisor to obtain progress data; has no overview of at-risk groups. |

### 1.3 Obstacles (current state, from blueprint §5)
| # | Area | Current obstacle |
|---|---|---|
| OB-1 | Scheduling assessments | Supervisors publish times via Zalo/email; times clash with teaching or personal schedules; groups compete for good slots through repeated messages; no per-slot capacity control. |
| OB-2 | Group information and documents | Members, topic and progress artifacts are sent via Drive/email; there is no single source of truth and no version history. |
| OB-3 | Question bank and materials | Questions for reviewing groups are prepared spontaneously; there is no categorized, reusable bank per topic managed by the admin. |
| OB-4 | In-meeting activities | Notes are kept in personal notebooks; new requirements raised by the supervisor are forgotten; minutes are written by the group leader days later, are incomplete, and are not verified by the supervisor. |
| OB-5 | Statistics and evaluation | Weekly and semester statistics must be requested from each supervisor; evaluation along topic fit, product quality and communication is subjective and stored in private files. |

### 1.4 Root causes
| # | Root cause | Obstacles caused |
|---|---|---|
| RC-1 | No dedicated system: the workflow runs on general tools (chat, email, spreadsheets) that have no concept of slot, capacity or booking. | OB-1, OB-2 |
| RC-2 | No shared data model linking group, topic, booking, meeting, requirement, minutes and evaluation, so data cannot be aggregated or traced. | OB-2, OB-4, OB-5 |
| RC-3 | Manual, person-dependent record keeping (minutes, requirement lists, scores) with no required format, no sign-off and no audit trail. | OB-4, OB-5 |
| RC-4 | No standardized evaluation criteria and weights; no requirement to record evidence at the time of assessment. | OB-5 |
| RC-5 | No automatic confirmations or reminders, so no-shows and last-minute changes are common. | OB-1 |
| RC-6 | Topic knowledge (questions, guidance notes) is owned by individual supervisors instead of being curated centrally by the admin. | OB-3 |

### 1.5 Frequency
The semester lasts 15 weeks (C-001). Frequencies below marked *(estimate)* should be confirmed with instructors.

| Problem | Frequency |
|---|---|
| Arranging check-ins / assessments | Every week for every group *(estimate)*, with peaks at milestone reviews and defenses |
| Sending and updating documents | Before every assessment round, several times per round per group |
| Preparing review questions | Before every assessment round and at the start of each semester |
| Recording requirements and writing minutes | In every meeting session |
| Compiling statistics | Weekly (progress) and at the end of the semester (final report) |
| Evaluating groups | At each assessment milestone and at the end of the semester |

### 1.6 Impact
- **Time lost:** supervisors and group leaders spend significant time every week on coordination and paperwork instead of supervision. Blueprint target: cut the time to complete minutes by 70% (BR-003).
- **Scheduling failures:** double-booked or over-capacity sessions and missed meetings delay projects and are unfair to groups.
- **Lost requirements:** requirements agreed in meetings are forgotten or disputed later, which causes rework and scope conflicts.
- **Low transparency:** students have no verified record of feedback; the department cannot see real progress or detect at-risk groups early.
- **Weak evaluation evidence:** scores are hard to justify during appeals and quality inspections.

### 1.7 Problem statement (one sentence)
> The problem of **fragmented, manual scheduling, record keeping and evaluation of project supervision** affects **students, group leaders, instructors, the academic admin and the department head**; its impact is **wasted time, scheduling conflicts, lost requirements and weakly evidenced evaluation**. A successful solution provides **conflict-free, capacity-controlled booking; a central record of groups, topics and artifacts; an admin-curated question bank; traceable requirements and two-party signed minutes; standardized three-dimensional evaluation; and weekly/semester statistics**.

---

## 2. Vision and Objectives

### 2.1 Vision
One web platform that manages the whole supervision lifecycle — from booking a slot, through the meeting and its signed minutes, to evaluation and reporting — so that supervisors spend their time guiding students, and every decision, requirement and score is traceable.

### 2.2 Business objectives (BR IDs from blueprint §6)
| ID | Objective | Success metric | Source | Capability |
|---|---|---|---|---|
| BR-001 | Automate scheduling and eliminate schedule conflicts for meetings and assessments. | 100% of sessions are booked through the system; 0 double-bookings and 0 capacity overflows. | intent §1–§3; blueprint §1 | CAP-01, CAP-02 |
| BR-002 | Centralize group records, progress artifacts and the topic question bank on one platform. | 100% of active groups have members, topic and supervisor recorded; 100% of artifacts for assessment rounds are submitted through the system *(proposed metric)*. | intent §1–§2 | CAP-03, CAP-04, CAP-05 |
| BR-003 | Increase transparency and traceability of meetings through requirement logging and automatically drafted minutes. | 100% of new requirements have a stable ID and a link to their session; time to complete minutes reduced by 70%. | intent §1, §4; blueprint §1 | CAP-06 |
| BR-004 | Standardize evaluation along three criteria with recorded evidence. | 100% of groups are evaluated on all three dimensions with comments before the end of the semester. | intent §4; blueprint §1 | CAP-07 |
| BR-005 | Provide a weekly and semester view of progress and delay risk. | Weekly and semester statistics are available without manual consolidation; figures update immediately after minutes are approved (RULE-022). | intent §2, §4 | CAP-08 |

---

## 3. Stakeholder Register

| ID | Stakeholder | Role / responsibility | Decision / approval | Interest | Influence | Key needs | System role |
|---|---|---|---|---|---|---|---|
| ST-001 | Student (Group Member) | Attends meetings, reads questions/materials, follows group progress and evaluation. | Confirms personal information. | High | Low | See own group's schedule, artifacts, requirements, minutes and published evaluation. | `STUDENT` |
| ST-002 | Group Leader | Represents the group: books slots, submits artifacts, takes notes, submits minutes. | Signs minutes on the student side. | High | Medium | Book/cancel slots, submit artifacts, log requirements, generate and submit minutes. | `GROUP_LEADER` |
| ST-003 | Instructor / Supervisor | Publishes available slots, runs meetings, logs requirements, scores groups, approves minutes. | Final decision on scores and minutes approval. | High | High | Manage own slots, review artifacts, use question bank, approve minutes, evaluate groups. | `INSTRUCTOR` |
| ST-004 | Academic Admin | Manages topics, question bank, department-wide slots, slot policy and user accounts. | Approves topic list and slot allocation policy. | High | High | Manage users, topics, questions, department slots; view reports. | `ADMIN` |
| ST-005 | Academic Board / Dept Head | Monitors course progress through weekly and end-of-semester reports. | Accepts the course's training results. | Medium | High | Read-only weekly/semester statistics and at-risk groups. | ⚪ No dedicated role yet; reports are Admin-only today |
| ST-006 | Development Team (Group 2) | Builds, tests and operates the system. | Technical decisions (ADRs). | High | Medium | Clear, confirmed requirements and rules. | — |
| ST-007 | University IT / Training Department | Owns student/instructor master data, email domain and Google Workspace. | Data import method (OQ-002), SSO access. | Low | Medium | Secure integration, data protection. | External |

---

## 4. Scope and Capabilities

### 4.1 In scope (v1)
| ID | Capability | Description | Objectives | Delivery increment |
|---|---|---|---|---|
| CAP-01 | Assessment scheduling (Calendly model) | Instructors/Admin publish slots (date, time, duration, capacity in groups, offline room or online link); Group Leaders browse and book; capacity and conflicts are enforced; bookings can be cancelled outside the late-cancellation window. | BR-001 | Sprint 2 |
| CAP-02 | Notifications and calendar export | Booking confirmation, reminders 24h and 2h before the session, change/cancellation notices; `.ics` / Google Calendar link export (A-006). | BR-001 | Sprint 2 |
| CAP-03 | User and access management | Local accounts restricted to the school domain, Google SSO, four roles with RBAC, admin user management. | BR-002 | Sprint 1 |
| CAP-04 | Group management | Create groups, assign topic and supervisor, manage members and one leader; students may create or join a group themselves. | BR-002 | Sprint 1 |
| CAP-05 | Topic, question bank and materials | Admin manages topics and categorized questions with guidance notes; instructors search them when preparing reviews. | BR-002 | Sprint 1 |
| CAP-06 | Artifacts, in-meeting support and minutes | Submit versioned artifacts per assessment round; run a meeting session from a booking; log new requirements with priority; generate draft minutes from text; two-party sign-off. | BR-002, BR-003 | Sprint 3–4 |
| CAP-07 | Three-dimensional evaluation | Score each group 0–100 on topic fit, product quality and communication with comments; weighted total; publish to the group. | BR-004 | Sprint 5 |
| CAP-08 | Statistics and reporting | Weekly and semester dashboard: sessions held, participation rate, requirements opened/resolved, groups without bookings, progress trend; export for manual import into the school system. | BR-005 | Sprint 5 |
| CAP-09 | Audit trail | Immutable log of every state change on bookings, artifacts, meetings, requirements, minutes and evaluations. | BR-003, BR-004 | Cross-cutting |

### 4.2 Out of scope (v1, from blueprint §2)
- Live audio speech-to-text; minutes are produced from typed notes or an uploaded transcript.
- Hosting video calls; the system only stores Google Meet / Zoom / Teams links.
- Multi-course or multi-year history; v1 targets the current semester.
- Automatic two-way grade synchronization with the school's training system (CSV/Excel export only).
- Two-way integration with the commercial Calendly API.

---

## 5. Business Rules

| ID | Rule | Source | Applies to |
|---|---|---|---|
| RULE-001 | A slot must not overlap another non-cancelled slot of the same instructor. | blueprint §5 step 1 | CAP-01 |
| RULE-002 | Slot capacity is counted in **groups**; default 1 group per slot, configurable to N groups for joint defenses. | A-001 | CAP-01 |
| RULE-003 | Instructors create and edit their own slots; the Admin can create department-wide assessment slots. | A-002 | CAP-01 |
| RULE-004 | A group may hold only **one active booking per assessment round**. | blueprint §5 step 2 | CAP-01 |
| RULE-005 | Only the Group Leader of a group may book or cancel on behalf of that group; an Instructor/Admin may also cancel. | blueprint §11, API-004 | CAP-01 |
| RULE-006 | A slot becomes `FULL` as soon as booked groups reach capacity and returns to `AVAILABLE` when a booking is cancelled. | UC-002, US-003 | CAP-01 |
| RULE-007 | A booking cannot be cancelled less than **2 hours** before the slot start time (late cancellation). | API-004 | CAP-01 |
| RULE-008 | Reminders are sent **24 hours and 2 hours** before each session to the group and the instructor. | FR-003 | CAP-02 |
| RULE-009 | Instructors who have not published slots for the next week receive a reminder 3 days in advance. | R-003 | CAP-02 |
| RULE-010 | If an instructor cancels or moves a slot, affected groups are notified urgently and get priority to book a replacement slot. | R-004 | CAP-01, CAP-02 |
| RULE-011 | A group has at most **5 active members** and exactly one active leader; a student belongs to at most one active group. | code (`StudentGroupService`) | CAP-04 |
| RULE-012 | A student who creates a group becomes its Group Leader; removing the leader demotes them back to Student. | code | CAP-04 |
| RULE-013 | Self-registration is allowed only for school e-mail domains (default `@fpt.edu.vn`); self-registered accounts are always `STUDENT`. | A-005, code | CAP-03 |
| RULE-014 | Artifact submission for a session is locked **2 hours** before the meeting starts (configurable). | blueprint §5 step 3 | CAP-06 |
| RULE-015 | Re-submitting an artifact with the same title creates a new version and marks the previous one `SUPERSEDED`; only an Instructor/Admin can accept an artifact. | code, blueprint §8 | CAP-06 |
| RULE-016 | A meeting session can only be opened from a `CONFIRMED` booking, and only one session exists per booking. | code | CAP-06 |
| RULE-017 | Every new requirement must have a title, a short description, a priority (High/Medium/Low) and a responsible person; it starts `OPEN` and receives a stable ID (e.g. `REQ-012`). | blueprint §5 step 4, US-004 | CAP-06 |
| RULE-018 | Minutes are drafted only from text (notes or transcript). The Group Leader reviews and submits; the Instructor approves or rejects (needs revision). | A-003, UC-003 | CAP-06 |
| RULE-019 | Minutes become `APPROVED` only when both sides have signed, within **48 hours** after the meeting; approved minutes are locked. | blueprint §5 step 5, US-006 | CAP-06 |
| RULE-020 | Each evaluation dimension is scored 0–100; the weighted total uses Topic Fit 40%, Product Quality 40%, Communication 20%, adjustable per course. | A-004 (unconfirmed), blueprint §5 step 6 | CAP-07 |
| RULE-021 | Students see an evaluation only after it is `PUBLISHED`; evaluation scores and confidential comments are Restricted data. | blueprint §11 | CAP-07 |
| RULE-022 | Statistics are updated immediately after each set of minutes is approved. | blueprint §5 step 7 | CAP-08 |
| RULE-023 | Every booking change, score change and minutes change is written to an immutable audit trail with timestamp and user ID. | NFR-006, blueprint §11 | CAP-09 |
| RULE-024 | Group artifacts and minutes are visible only to the group and its supervisor (temporary default for OQ-001). | blueprint §15 | CAP-04, CAP-06 |
| RULE-025 | Semester data stays active for 6 months and is archived for at least 1 year (3 following semesters) before any purge. | blueprint §11 | All |

---

## 6. Constraints, Assumptions and Dependencies

### 6.1 Constraints
| ID | Constraint | Source |
|---|---|---|
| C-001 | The system follows the 15-week semester and the school's exam calendar. | blueprint §4 |
| C-002 | The web UI must be responsive on desktop and mobile browsers. | blueprint §4 |
| C-003 | Backend technology is Java / Spring Boot with PostgreSQL (already built; see `04-ADD.md`). | code |
| C-004 | v1 covers one course/semester cycle only. | intent §6 |
| C-005 | Minutes are generated from text input only (no audio). | intent §6, A-003 |
| C-006 | Delivered by a student team in 5 sprints within one semester. | blueprint §12 |
| C-007 | Must comply with the university's training regulations and student data protection rules. | blueprint §11 |

### 6.2 Assumptions
| ID | Assumption | Status |
|---|---|---|
| A-001 | Slot capacity is counted in number of groups (default 1, configurable N). | Confirmed |
| A-002 | Instructors create/edit their own slots; the Admin creates department-wide slots. | Confirmed |
| A-003 | Minutes are generated from typed notes or an uploaded transcript. | Confirmed |
| A-004 | 100-point scale; weights Topic Fit 40%, Product Quality 40%, Communication 20%, adjustable per course. | **Unconfirmed** |
| A-005 | Local accounts plus Google SSO restricted to `@fpt.edu.vn`. | Confirmed |
| A-006 | The system manages its own calendar and exports `.ics` / Google Calendar links; no Calendly API. | Confirmed |
| A-007 | One instructor's slot list is the source of truth; concurrent editing by several admins is rare. | intent §6 |
| A-008 | Sessions may be offline (room) or online (external meeting link). | intent §6 |

### 6.3 Open questions
| ID | Question | Owner | Temporary answer |
|---|---|---|---|
| OQ-001 | Are a group's documents and minutes visible to other groups? | Academic Board | Private to the group and its supervisor (RULE-024). |
| OQ-002 | Are accounts imported from Excel/CSV each semester or synchronized via the Training Department API? | IT Department | Excel/CSV import in Sprint 1. |
| OQ-003 | What exactly is an "assessment round" (week, milestone, or admin-defined period)? | Academic Admin | Not defined; code currently allows one active booking per group at a time. |
| OQ-004 | Should evaluations be per session, per milestone, or once per semester? | Instructors | One or more records per group; latest published counts *(to confirm)*. |
| OQ-005 | Should the Dept Head get its own role, or read reports through the Admin role? | Academic Board | Admin role only (current code). |
| OQ-006 | What are the thresholds that flag a group as at risk (e.g. missed sessions, open requirements)? | Instructors | Not defined. |

### 6.4 Decisions
| ID | Decision | Status |
|---|---|---|
| D-001 | Use the Calendly visual calendar as the UX standard for choosing and holding slots. | Decided |

### 6.5 Dependencies
| ID | Dependency | Needed for | Status |
|---|---|---|---|
| DEP-001 | E-mail delivery service (SMTP / transactional e-mail provider) | CAP-02 notifications | ⚪ Not integrated |
| DEP-002 | Google OAuth 2.0 / Google Workspace for `@fpt.edu.vn` | CAP-03 SSO | ⚪ Not integrated |
| DEP-003 | File storage for uploaded artifacts and transcripts | CAP-06 | ⚪ Client supplies `fileUrl` today |
| DEP-004 | Text-processing / AI service for drafting minutes | CAP-06 | 🟡 Deterministic template in code, no AI |
| DEP-005 | Student/instructor lists from the Training Department (OQ-002) | CAP-03, CAP-04 | ⚪ Import not built |
| DEP-006 | School academic calendar (exam weeks, holidays) | CAP-01 | ⚪ Not integrated |
| DEP-007 | PostgreSQL 16 database and Docker hosting | All | ✅ |
