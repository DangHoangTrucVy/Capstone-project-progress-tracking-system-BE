# 7. Mốc nộp, tiến độ tuần, tài liệu, cảnh báo và chấm điểm

Swagger tag: **Milestones** (3), **Weekly Progress** (6), **Documents** (6), **Warning Flags** (3), **Evaluations** (3).

> Phần này dùng vai trò Instructor, nằm ngoài phạm vi 3 vai trò của đợt lập nhóm.

```mermaid
sequenceDiagram
    autonumber
    actor AD as Admin
    actor LD as Team Leader
    actor IN as Instructor (supervisor)
    participant FE as Web Client
    participant BE as Core System / Backend
    participant FS as File Storage (local or S3)
    participant DB as Database

    rect rgb(235, 243, 255)
    Note over AD,DB: 7.1 MILESTONES OF THE SEMESTER
    AD->>FE: Add a checkpoint
    FE->>BE: POST /api/v1/milestones (semester, code, name, dueDate)
    BE->>DB: Save milestone (409 if the code exists in that semester)
    BE-->>FE: 201 Milestone
    LD->>FE: See what to submit
    FE->>BE: GET /api/v1/milestones?semester=
    BE-->>FE: 200 Milestones
    FE->>BE: GET /api/v1/milestones/{id}
    BE-->>FE: 200 Milestone
    end

    rect rgb(238, 252, 238)
    Note over AD,DB: 7.2 WEEKLY PROGRESS REPORT
    LD->>FE: Report this week (percentage, tasks per member)
    FE->>BE: POST /api/v1/groups/{groupId}/progress (weekNumber, percent, tasks)
    alt Week already reported
        BE-->>FE: 409
    else A task is assigned to someone outside the group
        BE-->>FE: 400
    else OK
        BE->>DB: Save report and tasks, outbox PROGRESS_REPORTED
        BE-->>FE: 201 Report
    end
    FE->>BE: PUT /api/v1/progress/{id} (tasks replaced as a whole)
    BE->>DB: Update report
    BE-->>FE: 200 Report
    IN->>FE: Read and comment
    FE->>BE: GET /api/v1/groups/{groupId}/progress
    BE-->>FE: 200 Reports, oldest week first
    FE->>BE: GET /api/v1/progress/{id}
    BE-->>FE: 200 Report
    FE->>BE: PUT /api/v1/progress/{id}/feedback (feedback)
    BE->>DB: Save feedback, outbox PROGRESS_FEEDBACK (403 if not the supervisor)
    BE-->>FE: 200 Report
    FE->>BE: GET /api/v1/groups/{groupId}/progress/summary
    BE-->>FE: 200 Latest week and percentage trend
    end

    rect rgb(255, 248, 235)
    Note over AD,DB: 7.3 DOCUMENTS (FILE OR LINK)
    alt Upload a file
        LD->>FE: Choose a file for a milestone
        FE->>BE: POST /api/v1/groups/{groupId}/artifacts (multipart file, title, milestoneId)
        BE->>FS: Store the file under groups/{groupId}
        FS-->>BE: storageKey
    else Submit a link
        LD->>FE: Paste a link
        FE->>BE: POST /api/v1/groups/{groupId}/artifacts (JSON title, url, milestoneId)
    end
    alt Both file and link, no title, milestone of another semester
        BE-->>FE: 400
    else OK
        BE->>DB: Save document (SUBMITTED, new version), outbox DOCUMENT_SUBMITTED
        BE-->>FE: 201 Document
    end
    Note over BE,FS: If the transaction fails, the stored file is deleted
    FE->>BE: GET /api/v1/groups/{groupId}/artifacts?milestoneId=
    BE-->>FE: 200 Page of documents
    FE->>BE: GET /api/v1/artifacts/{id}
    BE-->>FE: 200 Document
    IN->>FE: Download the file
    FE->>BE: GET /api/v1/artifacts/{id}/file
    BE->>FS: Load by storageKey
    BE-->>FE: 200 File (400 if the document is a link)
    alt Accept
        FE->>BE: POST /api/v1/artifacts/{id}/accept
        BE->>DB: Status ACCEPTED (400 if not SUBMITTED), outbox DOCUMENT_FEEDBACK
        BE-->>FE: 200 Document
    else Ask for changes
        FE->>BE: PUT /api/v1/artifacts/{id}/feedback (feedback)
        BE->>DB: Save feedback on the latest version, outbox DOCUMENT_FEEDBACK
        BE-->>FE: 200 Document
    end
    end

    rect rgb(255, 240, 240)
    Note over AD,DB: 7.4 WARNING FLAGS
    IN->>FE: Warn a late group or an inactive member
    FE->>BE: POST /api/v1/groups/{groupId}/warning-flags (type, memberId, note)
    BE->>DB: Save flag (400 if memberId is missing or not a member), outbox WARNING_FLAG_RAISED
    BE-->>FE: 201 Flag
    FE->>BE: GET /api/v1/groups/{groupId}/warning-flags?activeOnly=true
    BE-->>FE: 200 Flags
    FE->>BE: PUT /api/v1/warning-flags/{id}/resolve (note)
    BE->>DB: Flag resolved (409 if already), outbox WARNING_FLAG_RESOLVED
    BE-->>FE: 200 Flag
    end

    rect rgb(243, 243, 255)
    Note over AD,DB: 7.5 EVALUATION ON 3 CRITERIA
    IN->>FE: Score topic fit, product quality, communication
    FE->>BE: POST /api/v1/groups/{groupId}/evaluations (3 scores, feedback)
    BE->>BE: Weighted total
    BE->>DB: Save evaluation (PUBLISHED), audit log (403 if not the supervisor)
    BE-->>FE: 201 Evaluation
    LD->>FE: See our scores
    FE->>BE: GET /api/v1/groups/{groupId}/evaluations
    BE-->>FE: 200 Page (students see published records only)
    FE->>BE: GET /api/v1/evaluations/{id}
    BE-->>FE: 200 Evaluation
    end
```
