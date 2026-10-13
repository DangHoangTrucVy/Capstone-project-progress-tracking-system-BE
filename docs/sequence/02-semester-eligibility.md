# 2. Thiết lập học kỳ và danh sách sinh viên đủ điều kiện

Swagger tag: **Semesters** (2), **Eligibility** (7), và 4 API cấu hình thời hạn của **Group Formation**.

```mermaid
sequenceDiagram
    autonumber
    actor AD as Admin
    actor ST as Student
    participant FE as Web Client
    participant BE as Core System / Backend
    participant XL as Excel Engine (Apache POI)
    participant DB as Database

    rect rgb(235, 243, 255)
    Note over AD,DB: 2.1 SEMESTER CALENDAR AND FORMATION WINDOW (YC14, YC17)
    AD->>FE: Set the semester start date
    FE->>BE: PUT /api/v1/semesters/{semester} (startDate)
    alt Reviews already scheduled and the new date moves them out of their week
        BE-->>FE: 409 / 400
    else OK
        BE->>DB: Save semester calendar
        BE-->>FE: 200 Calendar
    end
    FE->>BE: GET /api/v1/semesters/{semester}
    BE-->>FE: 200 Calendar (404 if not configured)
    AD->>FE: Set how long an Apply / Invite stays open
    FE->>BE: GET /api/v1/semesters/{semester}/join-settings
    BE-->>FE: 200 ttlHours (default 48)
    FE->>BE: PUT /api/v1/semesters/{semester}/join-settings (ttlHours)
    BE->>DB: Save join settings (400 if under 1 hour)
    BE-->>FE: 200 ttlHours
    AD->>FE: Set the group formation deadline
    FE->>BE: PUT /api/v1/semesters/{semester}/formation-deadline (deadline)
    BE->>DB: Save deadline
    BE-->>FE: 200 Deadline
    ST->>FE: Open "My group"
    FE->>BE: GET /api/v1/me/formation-window?semester=
    BE->>DB: Read deadline of the semester
    BE-->>FE: 200 open (true or false), deadline
    end

    rect rgb(255, 248, 235)
    Note over AD,DB: 2.2 IMPORT THE ELIGIBLE STUDENT LIST AT THE START OF TERM (YC03, YC05)
    alt Upload a file (.csv, .xlsx, .xls)
        AD->>FE: Choose the file
        FE->>BE: POST /api/v1/eligibility/import/file (multipart file)
        BE->>XL: Read rows, map Vietnamese or English headers (email, ho_ten, mssv, du_dieu_kien, ly_do)
        XL-->>BE: Entries
    else Send JSON
        AD->>FE: Paste or build the list
        FE->>BE: POST /api/v1/eligibility/import (students array or plain array)
    end
    loop Each line
        BE->>DB: Find student by email or student code, create the account if missing
        BE->>DB: Set eligible flag, reason, importedAt
    end
    alt No valid line
        BE-->>FE: 400 No student was imported, with the invalid lines
    else Some lines imported
        BE-->>FE: 200 created, updated, invalid lines, students
    end
    AD->>FE: Open the imported list
    FE->>BE: GET /api/v1/eligibility/students?eligible=&keyword=&page=
    BE->>DB: Page imported students
    BE-->>FE: 200 Page (same answer on GET /api/v1/eligibility/imported)
    FE->>BE: GET /api/v1/eligibility/imported
    BE-->>FE: 200 Page
    end

    rect rgb(255, 240, 240)
    Note over AD,DB: 2.3 MARK A STUDENT NOT ELIGIBLE (YC04)
    AD->>FE: Flag a student, give a reason
    FE->>BE: PUT /api/v1/eligibility/{userId} (eligible=false, reason)
    BE->>DB: eligible = false, reason
    BE->>DB: Cancel all their pending Apply / Invite
    opt The student is in a group
        BE->>DB: Remove them from every active group (whatever the lock)
        opt They were the Leader
            BE->>DB: Earliest-joined remaining member becomes Leader
        end
        BE->>DB: Outbox events MEMBER_REMOVED, LEADER_CHANGED
    end
    BE->>DB: Outbox event ELIGIBILITY_CHANGED (notifies the student)
    BE-->>FE: 200 User
    FE->>BE: GET /api/v1/eligibility/ineligible
    BE-->>FE: 200 Page of students not eligible
    ST->>FE: Open the home page
    FE->>BE: GET /api/v1/eligibility/me
    BE-->>FE: 200 eligible, reason (an ineligible student can sign in but gets 403 NOT_ELIGIBLE on formation actions)
    end
```

> A deadline only closes the window: after it, Apply, Invite, Accept and leave decisions return 409 to non-Admins.
> The BE has no automatic job that disbands groups under 3 members or cancels requests at the deadline.
