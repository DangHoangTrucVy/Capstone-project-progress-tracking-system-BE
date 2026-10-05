package com.capstone.tracking.group;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.group.dto.AddMemberRequest;
import com.capstone.tracking.group.dto.GroupMemberResponse;
import com.capstone.tracking.group.dto.StudentGroupCreateRequest;
import com.capstone.tracking.group.dto.StudentGroupResponse;
import com.capstone.tracking.group.dto.StudentGroupUpdateRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** FR-010: group roster management (blueprint.md §7 UC not explicit, underpins UC-002..UC-004). */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Tag(name = "Student Groups", description = "Group roster and membership management")
@SecurityRequirement(name = "bearerAuth")
public class StudentGroupController {

    private final StudentGroupService studentGroupService;

    public record LeaderRequest(@NotNull UUID userId) {}

    public record RosterReviewRequest(@NotNull Boolean approved, @Size(max = 1000) String note) {}

    /** A student creates a group and becomes its Leader (YC07); an Admin may provision one with topic/supervisor. */
    @Operation(summary = "Create a group (the creator becomes Leader)")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STUDENT','GROUP_LEADER')")
    public ResponseEntity<StudentGroupResponse> create(@Valid @RequestBody StudentGroupCreateRequest request,
                                                       @AuthenticationPrincipal User currentUser) {
        StudentGroup created = studentGroupService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + created.getId()))
                .body(StudentGroupResponse.from(created, studentGroupService.countActiveMembers(created.getId())));
    }

    /**
     * available=true lists only groups still recruiting (fewer than 5 active members, not locked). A student without a
     * group always sees just those, so they can pick where to Apply; a leader sees their own group.
     */
    @Operation(summary = "List groups (available=true: still recruiting)")
    @GetMapping
    public Page<StudentGroupResponse> list(@RequestParam(required = false) UUID supervisorId,
                                            @RequestParam(required = false) UUID topicId,
                                            @RequestParam(defaultValue = "false") boolean available,
                                            @ParameterObject Pageable pageable, @AuthenticationPrincipal User currentUser) {
        Page<StudentGroup> page = switch (currentUser.getRole()) {
            case GROUP_LEADER -> studentGroupService.listForMember(currentUser.getId(), pageable);
            case STUDENT -> studentGroupService.list(null, null, true, pageable);
            default -> studentGroupService.list(supervisorId, topicId, available, pageable);
        };
        Map<UUID, Long> counts = studentGroupService.countActiveMembers(
                page.getContent().stream().map(StudentGroup::getId).toList());
        return page.map(g -> StudentGroupResponse.from(g, counts.getOrDefault(g.getId(), 0L)));
    }

    @Operation(summary = "Get a group with its active members")
    @GetMapping("/{id}")
    public StudentGroupResponse getById(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        studentGroupService.requireCanView(id, currentUser);
        StudentGroup group = studentGroupService.getById(id);
        List<GroupMemberResponse> members = studentGroupService.listActiveMembers(id).stream()
                .map(GroupMemberResponse::from)
                .toList();
        return StudentGroupResponse.from(group, members.size(), members);
    }

    @Operation(summary = "Update a group (topic, supervisor...)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public StudentGroupResponse update(@PathVariable UUID id,
                                       @Valid @RequestBody StudentGroupUpdateRequest request,
                                       @AuthenticationPrincipal User currentUser) {
        StudentGroup updated = studentGroupService.update(id, request, currentUser);
        return StudentGroupResponse.from(updated, studentGroupService.countActiveMembers(updated.getId()));
    }

    @Operation(summary = "Add a member to a group")
    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('ADMIN','GROUP_LEADER')")
    public ResponseEntity<GroupMemberResponse> addMember(@PathVariable UUID id,
                                                         @Valid @RequestBody AddMemberRequest request,
                                                         @AuthenticationPrincipal User currentUser) {
        GroupMember member = studentGroupService.addMember(id, request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupMemberResponse.from(member));
    }

    /** The Leader kicks a member before Locked (YC18); an Admin may remove anyone at any time (YC19). */
    @Operation(summary = "Remove a member from a group")
    @DeleteMapping("/{id}/members/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','GROUP_LEADER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id,
                             @PathVariable UUID memberId,
                             @AuthenticationPrincipal User currentUser) {
        studentGroupService.removeMember(id, memberId, currentUser);
    }

    /** YC20: Admin replaces the leader (on the supervisor's report); the new leader must already be a member. */
    @Operation(summary = "Replace the group leader")
    @PutMapping("/{id}/leader")
    @PreAuthorize("hasRole('ADMIN')")
    public GroupMemberResponse replaceLeader(@PathVariable UUID id, @Valid @RequestBody LeaderRequest request) {
        return GroupMemberResponse.from(studentGroupService.replaceLeader(id, request.userId()));
    }

    /** YC19: after Locked only an Admin edits the roster. */
    @Operation(summary = "Lock the group roster")
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public StudentGroupResponse lock(@PathVariable UUID id) {
        return respond(studentGroupService.setLocked(id, true));
    }

    @Operation(summary = "Unlock the group roster")
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public StudentGroupResponse unlock(@PathVariable UUID id) {
        return respond(studentGroupService.setLocked(id, false));
    }

    /** YC16: the Leader sends the member list to the supervisor. */
    @Operation(summary = "Leader submits the roster to the supervisor")
    @PostMapping("/{id}/roster/submit")
    @PreAuthorize("hasRole('GROUP_LEADER')")
    public StudentGroupResponse submitRoster(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return respond(studentGroupService.submitRoster(id, currentUser));
    }

    @Operation(summary = "Supervisor approves or rejects the roster")
    @PostMapping("/{id}/roster/review")
    @PreAuthorize("hasAnyRole('INSTRUCTOR','ADMIN')")
    public StudentGroupResponse reviewRoster(@PathVariable UUID id, @Valid @RequestBody RosterReviewRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        return respond(studentGroupService.reviewRoster(id, currentUser, request.approved(), request.note()));
    }

    private StudentGroupResponse respond(StudentGroup group) {
        return StudentGroupResponse.from(group, studentGroupService.countActiveMembers(group.getId()));
    }
}
