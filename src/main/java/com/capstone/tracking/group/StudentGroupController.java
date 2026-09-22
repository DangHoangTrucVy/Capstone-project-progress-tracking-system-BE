package com.capstone.tracking.group;

import com.capstone.tracking.group.dto.AddMemberRequest;
import com.capstone.tracking.group.dto.GroupMemberResponse;
import com.capstone.tracking.group.dto.StudentGroupCreateRequest;
import com.capstone.tracking.group.dto.StudentGroupResponse;
import com.capstone.tracking.group.dto.StudentGroupUpdateRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** FR-010: group roster management (blueprint.md §7 UC not explicit, underpins UC-002..UC-004). */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Tag(name = "Student Groups", description = "Group roster and membership management")
@SecurityRequirement(name = "bearerAuth")
public class StudentGroupController {

    private final StudentGroupService studentGroupService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','STUDENT')")
    public ResponseEntity<StudentGroupResponse> create(@Valid @RequestBody StudentGroupCreateRequest request,
                                                       @AuthenticationPrincipal User currentUser) {
        StudentGroup created = studentGroupService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + created.getId()))
                .body(StudentGroupResponse.from(created, studentGroupService.countActiveMembers(created.getId())));
    }

    /** available=true lists only groups that still have room (fewer than 5 active members). */
    @GetMapping
    public Page<StudentGroupResponse> list(@RequestParam(required = false) UUID supervisorId,
                                            @RequestParam(required = false) UUID topicId,
                                            @RequestParam(defaultValue = "false") boolean available,
                                            Pageable pageable) {
        Page<StudentGroup> page = studentGroupService.list(supervisorId, topicId, available, pageable);
        Map<UUID, Long> counts = studentGroupService.countActiveMembers(
                page.getContent().stream().map(StudentGroup::getId).toList());
        return page.map(g -> StudentGroupResponse.from(g, counts.getOrDefault(g.getId(), 0L)));
    }

    @GetMapping("/{id}")
    public StudentGroupResponse getById(@PathVariable UUID id) {
        StudentGroup group = studentGroupService.getById(id);
        List<GroupMemberResponse> members = studentGroupService.listActiveMembers(id).stream()
                .map(GroupMemberResponse::from)
                .toList();
        return StudentGroupResponse.from(group, members.size(), members);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    public StudentGroupResponse update(@PathVariable UUID id, @Valid @RequestBody StudentGroupUpdateRequest request) {
        StudentGroup updated = studentGroupService.update(id, request);
        return StudentGroupResponse.from(updated, studentGroupService.countActiveMembers(updated.getId()));
    }

    @PostMapping("/{id}/join")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<GroupMemberResponse> join(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        GroupMember member = studentGroupService.join(id, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupMemberResponse.from(member));
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','GROUP_LEADER')")
    public ResponseEntity<GroupMemberResponse> addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberRequest request) {
        GroupMember member = studentGroupService.addMember(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupMemberResponse.from(member));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR','GROUP_LEADER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id, @PathVariable UUID memberId) {
        studentGroupService.removeMember(id, memberId);
    }
}
