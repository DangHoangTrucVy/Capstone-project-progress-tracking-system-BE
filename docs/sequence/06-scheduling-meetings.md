# 6. Lịch rảnh, đặt lịch, buổi họp, biên bản và yêu cầu

Swagger tag: **Schedule Slots** (3), **Bookings** (2), **Meetings** (4), **Meeting Minutes** (3), **Requirement Logs** (3).

> Phần này dùng vai trò Instructor, nằm ngoài phạm vi 3 vai trò của đợt lập nhóm.

```mermaid
sequenceDiagram
    autonumber
    actor IN as Instructor
    actor LD as Team Leader
    participant FE as Web Client
    participant BE as Core System / Backend
    participant DB as Database

    rect rgb(235, 243, 255)
    Note over IN,DB: 6.1 INSTRUCTOR OPENS AVAILABILITY SLOTS
    IN->>FE: Add a free time window
    FE->>BE: POST /api/v1/slots (startTime, endTime, capacity, location)
    alt endTime not after startTime
        BE-->>FE: 400
    else Overlaps a slot, a review or a defense of this instructor
        BE-->>FE: 409
    else OK
        BE->>DB: Save slot (AVAILABLE)
        BE-->>FE: 201 Slot
    end
    LD->>FE: Look for a slot
    FE->>BE: GET /api/v1/slots?instructorId=&status=AVAILABLE&fromDate=&toDate=
    BE-->>FE: 200 Page of slots
    FE->>BE: GET /api/v1/slots/{id}
    BE-->>FE: 200 Slot
    end

    rect rgb(238, 252, 238)
    Note over IN,DB: 6.2 LEADER BOOKS A SLOT OR CANCELS IT
    LD->>FE: Click "Book"
    FE->>BE: POST /api/v1/slots/{id}/book (groupId, notes)
    BE->>DB: Lock the schedule and the group
    alt Not this group's Leader
        BE-->>FE: 403
    else Group already has an open booking
        BE-->>FE: 400 Finish or cancel that meeting first
    else Slot full or closed, or a meeting that day, or a review or defense at that time
        BE-->>FE: 409
    else Less than 24 hours before the slot
        BE-->>FE: 400
    else OK
        BE->>DB: Save booking (CONFIRMED), bookedCount + 1, slot FULL when no room left
        BE->>DB: Audit log, outbox BOOKING_CONFIRMED (notifies the instructor)
        BE-->>FE: 200 Booking
    end
    opt Cancel
        LD->>FE: Click "Cancel booking", give a reason
        FE->>BE: DELETE /api/v1/bookings/{id} (reason)
        alt Not CONFIRMED, meeting already started, or less than 2 hours before
            BE-->>FE: 400
        else OK
            BE->>DB: Booking CANCELLED, meeting CANCELLED, bookedCount - 1, slot AVAILABLE again
            BE->>DB: Outbox BOOKING_CANCELLED
            BE-->>FE: 200
        end
    end
    end

    rect rgb(255, 248, 235)
    Note over IN,DB: 6.3 THE MEETING
    LD->>FE: Open the confirmed booking
    FE->>BE: POST /api/v1/bookings/{bookingId}/meetings
    BE->>DB: Save meeting (SCHEDULED) (400 if the booking is not CONFIRMED, 409 if one exists)
    BE-->>FE: 201 Meeting
    FE->>BE: PUT /api/v1/meetings/{id}/start
    BE->>DB: Status IN_PROGRESS (400 before the slot starts)
    BE-->>FE: 200 Meeting
    IN->>FE: Log a requirement raised in the meeting
    FE->>BE: POST /api/v1/meetings/{id}/requirements (title, priority, description)
    BE->>DB: Save requirement (400 if the meeting is cancelled)
    BE-->>FE: 201 Requirement
    FE->>BE: GET /api/v1/meetings/{id}/requirements
    BE-->>FE: 200 Page of requirements
    LD->>FE: Update a requirement
    FE->>BE: PUT /api/v1/requirements/{id} (status, note)
    BE->>DB: Update requirement
    BE-->>FE: 200 Requirement
    FE->>BE: PUT /api/v1/meetings/{id}/end (rawNotes)
    BE->>DB: Status CONCLUDED, booking ATTENDED (the group may book again)
    BE-->>FE: 200 Meeting (400 before the slot ends)
    FE->>BE: GET /api/v1/meetings/{id}
    BE-->>FE: 200 Meeting
    end

    rect rgb(243, 243, 255)
    Note over IN,DB: 6.4 MINUTES: GENERATE, LEADER SUBMITS, INSTRUCTOR APPROVES
    LD->>FE: Click "Generate minutes"
    FE->>BE: POST /api/v1/meetings/{id}/minutes/generate (notes)
    BE->>DB: Read the notes and the requirements
    BE->>BE: Fill the template (Objectives, Discussion, New Requirements, Conclusion)
    BE->>DB: Save minutes as DRAFT (400 if already submitted or the notes are empty)
    BE-->>FE: 200 Draft and sections
    LD->>FE: Edit and sign
    FE->>BE: PUT /api/v1/meetings/{id}/minutes/sign (finalContent)
    BE->>DB: Status UNDER_REVIEW, studentSignedAt (409 if not DRAFT or REJECTED)
    BE-->>FE: 200 Minutes
    IN->>FE: Approve or reject
    FE->>BE: PUT /api/v1/meetings/{id}/minutes/sign (decision, comments)
    BE->>DB: Status APPROVED or REJECTED, instructorSignedAt, audit log
    BE-->>FE: 200 Minutes (409 if not UNDER_REVIEW)
    FE->>BE: GET /api/v1/meetings/{id}/minutes
    BE-->>FE: 200 Minutes
    end
```
