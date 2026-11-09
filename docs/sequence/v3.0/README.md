# Sequence diagrams: Team Formation Website v3.0

UML 2 sequence diagrams for the whole system, drawn from the current backend code (requirement v3.0) in the Visual Paradigm style:
- an `sd` frame;
- actors, plus boundary / control / entity lifelines named `:ClassName`;
- numbered messages (`1:`, `2:` …), where a solid arrow is a call and a dashed arrow is a return;
- `alt / opt / break / loop / ref` fragments with `[guard]` conditions.

The sources are PlantUML (`.puml`) and the images are `.png` and `.svg`. The shared theme is in `_vp-style.iuml`.

| Code | Scenario | Actors |
|---|---|---|
| [SD01](SD01-login-password.png) | Login with email and password | Admin, Moderator |
| [SD02](SD02-login-google.png) | Login with Google | Student |
| [SD03](SD03-manage-moderators-majors.png) | Manage Moderator accounts and majors | Admin |
| [SD04](SD04-create-semester-assign-moderator.png) | Create a semester and assign its Moderator | Admin |
| [SD05](SD05-rules-and-deadline.png) | Configure the rules and the common deadline | Moderator |
| [SD06](SD06-import-students.png) | Import the student list (preview → confirm) | Admin |
| [SD07](SD07-eligibility.png) | Import or change eligibility | Admin, Moderator |
| [SD08](SD08-create-rooms.png) | Room plan and bulk room creation | Moderator |
| [SD09](SD09-start-formation.png) | Phase check and start formation | Moderator |
| [SD10](SD10-join-room.png) | Join a room (empty / open / locked → request) | Student |
| [SD11](SD11-invite-and-answer-requests.png) | Invite, accept / reject / withdraw, like | Leader, Student |
| [SD12](SD12-leader-manages-room.png) | Rename, lock / unlock, remove a member | Leader |
| [SD13](SD13-leave-room-and-complaint.png) | Leave, Leader decision, complaint, Moderator decision | Member, Leader, Moderator |
| [SD14](SD14-leader-transfer.png) | Transfer the Leader role | Leader, Member |
| [SD15](SD15-finalize-request.png) | Finalize request and Moderator approval (group code) | Leader, Moderator |
| [SD16](SD16-deadline-auto-lock.png) | Lock rooms at the deadline, expire requests | Scheduler |
| [SD17](SD17-moderator-coordination.png) | Add, move, remove, appoint, merge, disband | Moderator |
| [SD18](SD18-finalize-round1-supplement.png) | Finalize round 1 and open the supplement phase | Moderator |
| [SD19](SD19-finalize-round2-close.png) | Finalize round 2 and close formation | Moderator |
| [SD20](SD20-statistics-and-export.png) | Statistics and Excel export | Moderator, Admin |
| [SD21](SD21-notifications.png) | Web (SSE) and email notifications | System |
| [SD22](SD22-profile-and-search.png) | Profile, room search, teammate search | Student |

## Re-render after editing

```bash
cd docs/sequence/v3.0
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/data" -w /data plantuml/plantuml:1.2024.7 -tpng -charset UTF-8 "SD*.puml"
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/data" -w /data plantuml/plantuml:1.2024.7 -tsvg -charset UTF-8 "SD*.puml"
```

## Redrawing in Visual Paradigm

Each diagram maps one-to-one onto VP's sequence diagram tools:

| In the diagram | In Visual Paradigm |
|---|---|
| actor | Actor |
| `:XxxScreen` | Lifeline with the Boundary stereotype |
| `:XxxController` / `:XxxService` | Lifeline with the Control stereotype |
| `:Xxx` / `:XxxRepository` | Lifeline with the Entity stereotype |
| solid arrow | Message (call) |
| dashed arrow | Return Message |
| fragments | Combined Fragment (alt / opt / break / loop) with Interaction Operand guards |
