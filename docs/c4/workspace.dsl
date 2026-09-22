workspace "Capstone Tracking System" "C4 model for the Student Schedule and Guidance Management System (capstone-tracking-backend). Grounded in the actual code on branch feat/student-join-group, not just blueprint.md's plan — see docs/feature-list-and-architecture.md for the written write-up this model accompanies." {

    model {
        // ===== People =====
        student      = person "Student"       "A member of a capstone project group. Views the group's schedule, documents, questions and evaluation."
        groupLeader  = person "Group Leader"  "A student who leads their group: creates/joins the group, books assessment slots, submits artifacts, and signs meeting minutes."
        instructor   = person "Instructor"    "A supervisor who publishes assessment slots, conducts meetings, records requirements, approves minutes, and evaluates groups."
        admin        = person "Admin"         "Manages user accounts, topics and the question bank; views semester-wide progress reports."

        // ===== Primary software system =====
        trackingSystem = softwareSystem "Capstone Tracking System" "Centralises assessment scheduling, group/artifact management, in-meeting support (requirements + auto-generated minutes), and 3-dimensional evaluation for capstone supervision." {

            webApp = container "Web Application" "Single-page app used by all roles to browse slots, manage groups, and review meetings. Not part of this repository — inferred from the API's CORS allow-list (localhost:3000 / 5173)." "SPA (framework TBD)" "WebBrowser"

            api = container "Backend API" "Serves the REST API for auth, scheduling, groups, artifacts, meetings, evaluation and reporting. Exposes an OpenAPI/Swagger UI for interactive docs." "Java 17 / Spring Boot 3.3.4" {

                authComponent          = component "Auth & Security"          "Registration (domain-restricted), login, JWT issuance/validation, and per-request authentication."               "Spring Security + jjwt"
                userComponent          = component "User Management"          "Admin CRUD for user accounts."                                                                                       "Spring MVC + Spring Data JPA"
                topicComponent         = component "Topic Management"         "Create/list capstone topics."                                                                                        "Spring MVC + Spring Data JPA"
                questionBankComponent  = component "Question Bank"            "Per-topic question bank and guidance notes."                                                                         "Spring MVC + Spring Data JPA"
                groupComponent         = component "Student Group Management" "Group roster: create, self-join, add/remove member; enforces the 5-active-member cap."                               "Spring MVC + Spring Data JPA"
                schedulingComponent    = component "Assessment Scheduling"    "Calendly-style slot publishing and booking; pessimistic row-lock (SELECT ... FOR UPDATE) prevents over-booking."      "Spring MVC + Spring Data JPA"
                artifactComponent      = component "Artifact Submissions"     "Progress-artifact metadata: submit, version/supersede on re-submit with the same title, accept."                     "Spring MVC + Spring Data JPA"
                meetingComponent       = component "Meeting Support"         "Meeting sessions, requirement logs, and template-generated meeting minutes with 2-party sign-off."                     "Spring MVC + Spring Data JPA"
                evaluationComponent    = component "Evaluation"               "3-criteria scoring (Topic Fit / Product Quality / Communication) with weighted total, published immediately on save." "Spring MVC + Spring Data JPA"
                reportingComponent     = component "Reporting"                "Aggregate progress statistics (sessions held, attendance, open/closed requirements) for Admin."                     "Spring MVC"
                auditComponent         = component "Audit Trail"              "Immutable log of state-changing actions, written inside the same transaction as the change it documents."             "Spring + Spring Data JPA"
            }

            database = container "Database" "Stores all persistent application data; schema is fully owned by Flyway migrations (Hibernate ddl-auto=validate)." "PostgreSQL 16" "Database"
        }

        // ===== Relationships: People -> System (context level) =====
        student     -> trackingSystem "Views group progress, documents, and questions via"
        groupLeader -> trackingSystem "Books slots, submits artifacts, and signs meeting minutes via"
        instructor  -> trackingSystem "Publishes slots, runs meetings, and evaluates groups via"
        admin       -> trackingSystem "Manages users, topics, question bank, and reports via"

        // ===== Relationships: People -> Containers =====
        student     -> webApp "Uses" "HTTPS"
        groupLeader -> webApp "Uses" "HTTPS"
        instructor  -> webApp "Uses" "HTTPS"
        admin       -> webApp "Uses" "HTTPS"

        // ===== Relationships: Container -> Container =====
        webApp -> api      "Makes API calls to" "JSON/HTTPS, JWT Bearer"
        api    -> database "Reads from and writes to" "JDBC"
        api    -> database "Applies versioned schema migrations to" "Flyway"

        // ===== Relationships: Component -> Component (business dependencies) =====
        authComponent       -> userComponent         "Looks up user accounts / roles via"
        groupComponent      -> userComponent          "Validates member, leader and supervisor identities via"
        groupComponent      -> topicComponent          "Links a group to its assigned topic via"
        topicComponent      -> questionBankComponent   "Owns the question bank items of"
        schedulingComponent -> userComponent           "Associates a slot with its publishing instructor via"
        schedulingComponent -> groupComponent          "Associates a booking with the booking group via"
        schedulingComponent -> auditComponent          "Records booking create/cancel to" "same transaction"
        meetingComponent    -> schedulingComponent    "Creates a meeting session from a confirmed"
        meetingComponent    -> groupComponent          "Attributes requirement logs to"
        artifactComponent   -> groupComponent          "Attributes submissions to"
        artifactComponent   -> meetingComponent        "Optionally links a submission to a"
        evaluationComponent -> groupComponent          "Scores"
        evaluationComponent -> userComponent           "Attributes an evaluation to the scoring instructor via"
        reportingComponent  -> schedulingComponent    "Aggregates booking/session statistics from"
        reportingComponent  -> meetingComponent        "Aggregates requirement statistics from"
        reportingComponent  -> groupComponent          "Aggregates group statistics from"

        // ===== Relationships: Component -> Database =====
        authComponent         -> database "Reads from and writes to" "JDBC"
        userComponent         -> database "Reads from and writes to" "JDBC"
        topicComponent        -> database "Reads from and writes to" "JDBC"
        questionBankComponent -> database "Reads from and writes to" "JDBC"
        groupComponent        -> database "Reads from and writes to" "JDBC"
        schedulingComponent   -> database "Reads from and writes to (row-locked on booking)" "JDBC"
        artifactComponent     -> database "Reads from and writes to" "JDBC"
        meetingComponent      -> database "Reads from and writes to" "JDBC"
        evaluationComponent   -> database "Reads from and writes to" "JDBC"
        reportingComponent    -> database "Runs read-only aggregate queries against" "JDBC"
        auditComponent        -> database "Writes to" "JDBC"

        // ===== Deployment (matches docker-compose.yml) =====
        deploymentEnvironment "Docker Compose (local / single-host dev)" {
            deploymentNode "Docker Host" "Developer machine or single server running Docker Engine" "Docker" {
                deploymentNode "app container" "capstone-tracking-app" "eclipse-temurin:17-jre-alpine" {
                    containerInstance api
                }
                deploymentNode "postgres container" "capstone-tracking-db" "postgres:16-alpine" {
                    containerInstance database
                }
            }
        }
    }

    views {
        // Level 1: System Context
        systemContext trackingSystem "SystemContext" "Who uses the Capstone Tracking System and why." {
            include *
            autoLayout
        }

        // Level 2: Container
        container trackingSystem "Containers" "Internal structure: web client, backend API, database." {
            include *
            autoLayout
        }

        // Level 3: Component (inside the Backend API container)
        component api "ApiComponents" "Package-by-feature modules inside the Backend API, mirroring src/main/java/com/capstone/tracking/*." {
            include *
            autoLayout
        }

        // Deployment
        deployment trackingSystem "Docker Compose (local / single-host dev)" "Deployment" "How the system is deployed today, per docker-compose.yml." {
            include *
            autoLayout
        }

        // Dynamic: booking a slot (the concurrency-critical flow, NFR-002/R-001)
        dynamic trackingSystem "BookSlotFlow" "A Group Leader books an assessment slot; the last seat cannot be double-booked." {
            groupLeader -> webApp "Selects an available slot and confirms booking"
            webApp -> api "POST /api/v1/slots/{id}/book (Bearer JWT)"
            api -> database "SELECT ... FOR UPDATE the slot row, then insert Confirmed booking + audit row (same transaction)"
            api -> webApp "200 OK { bookingId, status: Confirmed } or 409 if the slot just filled"
            webApp -> groupLeader "Shows booking confirmation (or prompts to pick another slot)"
            autoLayout
        }

        // Dynamic: meeting minutes generation + two-party sign-off
        dynamic trackingSystem "MeetingMinutesFlow" "Auto-generating and signing off a meeting's minutes." {
            groupLeader -> webApp "Enters meeting notes and requests a minutes draft"
            webApp -> api "POST /api/v1/meetings/{id}/minutes/generate"
            api -> database "Reads raw notes + requirement logs, writes a Draft MeetingMinute (template-based, not AI-generated)"
            groupLeader -> webApp "Reviews draft and submits"
            webApp -> api "PUT /api/v1/meetings/{id}/minutes/sign (Leader submit)"
            instructor -> webApp "Reviews and approves/rejects"
            webApp -> api "PUT /api/v1/meetings/{id}/minutes/sign (Instructor approve/reject)"
            api -> database "Updates MeetingMinute status to Approved/Rejected"
            autoLayout
        }

        styles {
            element "Person" {
                shape Person
                background #08427B
                color #ffffff
            }
            element "Software System" {
                background #1168BD
                color #ffffff
            }
            element "Container" {
                background #438DD5
                color #ffffff
            }
            element "Component" {
                background #85BBF0
                color #000000
            }
            element "Database" {
                shape Cylinder
            }
            element "WebBrowser" {
                shape WebBrowser
            }
        }
    }

}
