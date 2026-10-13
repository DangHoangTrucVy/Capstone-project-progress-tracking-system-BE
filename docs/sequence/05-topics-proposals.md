# 5. Đề tài, ngân hàng câu hỏi và duyệt đề tài

Swagger tag: **Topics** (4), **Question Bank** (2), **Topic Proposals** (9).

> Phần này dùng vai trò Instructor và Council. Hai vai trò này nằm ngoài phạm vi 3 vai trò (Student, Leader, Admin) của đợt lập nhóm, nhưng API vẫn có trên Swagger.

```mermaid
sequenceDiagram
    autonumber
    actor AD as Admin
    actor LD as Team Leader
    participant FE as Web Client
    participant BE as Core System / Backend
    participant DB as Database

    rect rgb(235, 243, 255)
    Note over AD,DB: 5.1 TOPIC CATALOGUE
    AD->>FE: Add a topic
    FE->>BE: POST /api/v1/topics (topicCode, title, description, status)
    BE->>DB: Check the code is unused, save topic
    BE-->>FE: 201 Topic (409 if the code is taken)
    FE->>BE: PUT /api/v1/topics/{id} (title, description, status)
    BE->>DB: Update topic
    BE-->>FE: 200 Topic
    LD->>FE: Browse topics
    FE->>BE: GET /api/v1/topics?status=PUBLISHED
    BE-->>FE: 200 Page of topics
    FE->>BE: GET /api/v1/topics/{id}
    BE-->>FE: 200 Topic
    end

    rect rgb(255, 248, 235)
    Note over AD,DB: 5.2 QUESTION BANK OF A TOPIC
    LD->>FE: Send a question for our topic
    FE->>BE: POST /api/v1/topics/{topicId}/questions (content, category)
    alt Leader and the topic is not their group's topic
        BE-->>FE: 403
    else OK
        BE->>DB: Save question
        BE-->>FE: 201 Question
    end
    FE->>BE: GET /api/v1/topics/{topicId}/questions?category=
    BE-->>FE: 200 Page of questions
    end
```

```mermaid
sequenceDiagram
    autonumber
    actor LD as Team Leader
    actor IN as Instructor (supervisor)
    actor CO as Council
    actor AD as Admin
    participant FE as Web Client
    participant BE as Core System / Backend
    participant DB as Database

    rect rgb(238, 252, 238)
    Note over LD,DB: 5.3 LEADER SUBMITS TOPICS, SUPERVISOR FORWARDS ONE
    LD->>FE: Fill the topic list (10 topics in round 1)
    FE->>BE: POST /api/v1/groups/{groupId}/topic-proposals (items)
    alt No supervisor yet, or wrong number of topics
        BE-->>FE: 400
    else A proposal is under review, topic already approved, 4 rounds used, round not open
        BE-->>FE: 409 / 400
    else OK
        BE->>DB: Save proposal (PENDING_INSTRUCTOR), outbox TOPIC_PROPOSAL_SUBMITTED
        BE-->>FE: 201 Proposal
    end
    FE->>BE: GET /api/v1/groups/{groupId}/topic-proposals
    BE-->>FE: 200 Proposals of the group
    IN->>FE: Open the review queue
    FE->>BE: GET /api/v1/topic-proposals?status=PENDING_INSTRUCTOR
    BE-->>FE: 200 Page of proposals
    FE->>BE: GET /api/v1/topic-proposals/{id}
    BE-->>FE: 200 Proposal with its topics
    IN->>FE: Pick one topic, add a note
    FE->>BE: POST /api/v1/topic-proposals/{id}/forward (itemId, note)
    BE->>DB: Mark the topic selected, status PENDING_COUNCIL, council deadline (14 or 10 days)
    BE->>DB: Audit log, outbox TOPIC_FORWARDED_TO_COUNCIL
    BE-->>FE: 200 Proposal (409 if not waiting for pre-review)
    end

    rect rgb(255, 240, 240)
    Note over LD,DB: 5.4 COUNCIL DECIDES
    CO->>FE: Approve or reject the forwarded topic
    FE->>BE: POST /api/v1/topic-proposals/{id}/decision (decision, feedback)
    alt Not forwarded, or approval after the council deadline
        BE-->>FE: 409
    else Reject without feedback
        BE-->>FE: 400
    else Approved
        BE->>DB: Create a PUBLISHED topic, set it on the group, group FORMED to ACTIVE
        BE->>DB: Outbox TOPIC_APPROVED
        BE-->>FE: 200 Proposal
    else Rejected
        BE->>DB: Status REJECTED, outbox TOPIC_REJECTED with the rounds left
        BE-->>FE: 200 Proposal
    end
    end

    rect rgb(243, 243, 255)
    Note over LD,DB: 5.5 ADMIN OPENS A RESUBMISSION ROUND (ROUND 2, 3 OR 4)
    AD->>FE: Open round N (opensAt, closesAt, at most 10 days)
    FE->>BE: POST /api/v1/proposal-rounds (semester, roundNumber, opensAt, closesAt)
    BE->>DB: Save or reopen the round
    BE->>DB: Outbox PROPOSAL_ROUND_OPENED to every group rejected in round N-1
    BE-->>FE: 200 Round
    FE->>BE: GET /api/v1/proposal-rounds?semester=
    BE-->>FE: 200 Rounds
    AD->>FE: Close the round early
    FE->>BE: PUT /api/v1/proposal-rounds/{id}/close
    BE->>DB: closed = true, audit log
    BE-->>FE: 200 Round
    end
```
