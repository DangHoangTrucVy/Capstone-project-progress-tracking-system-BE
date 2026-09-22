package com.capstone.tracking.group;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.dto.AddMemberRequest;
import com.capstone.tracking.group.dto.StudentGroupCreateRequest;
import com.capstone.tracking.group.dto.StudentGroupUpdateRequest;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicService;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-010 group/member management underpinning every later sprint (Booking, ArtifactSubmission, etc.
 * all key off StudentGroup / GroupMember per blueprint.md §8).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentGroupService {

    public static final int MAX_MEMBERS = 5;

    private final StudentGroupRepository studentGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final TopicService topicService;
    private final UserService userService;

    /**
     * A STUDENT creating a group becomes its leader: they are added as the active leader member and
     * their global role is promoted to GROUP_LEADER. Admin/Instructor creations leave membership empty.
     */
    @Transactional
    public StudentGroup create(StudentGroupCreateRequest request, User creator) {
        boolean studentCreator = creator.getRole() == Role.STUDENT;
        User leader = studentCreator ? userService.getById(creator.getId()) : null;
        if (leader != null && groupMemberRepository.existsByUserIdAndStatus(leader.getId(), MemberStatus.ACTIVE)) {
            throw new ConflictException("You already belong to a group and cannot create another one");
        }
        if (studentGroupRepository.existsByGroupCodeIgnoreCase(request.groupCode())) {
            throw new ConflictException("Group code " + request.groupCode() + " is already in use");
        }
        Topic topic = request.topicId() != null ? topicService.getById(request.topicId()) : null;
        User supervisor = request.supervisorId() != null
                ? requireRole(request.supervisorId(), Role.INSTRUCTOR, Role.ADMIN)
                : null;

        StudentGroup group = StudentGroup.builder()
                .groupCode(request.groupCode())
                .topic(topic)
                .supervisor(supervisor)
                .semester(request.semester())
                .status(GroupStatus.FORMED)
                .build();
        StudentGroup saved = studentGroupRepository.save(group);

        if (leader != null) {
            leader.setRole(Role.GROUP_LEADER);
            groupMemberRepository.save(GroupMember.builder()
                    .group(saved)
                    .user(leader)
                    .isLeader(true)
                    .joinedAt(Instant.now())
                    .status(MemberStatus.ACTIVE)
                    .build());
        }
        return saved;
    }

    public StudentGroup getById(UUID id) {
        return studentGroupRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("StudentGroup", id));
    }

    public long countActiveMembers(UUID groupId) {
        return groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE);
    }

    public Map<UUID, Long> countActiveMembers(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : groupMemberRepository.countByGroupIds(groupIds, MemberStatus.ACTIVE)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** availableOnly (groups with fewer than MAX_MEMBERS active members) takes priority over the other filters. */
    public Page<StudentGroup> list(UUID supervisorId, UUID topicId, boolean availableOnly, Pageable pageable) {
        if (availableOnly) {
            return studentGroupRepository.findNotFull(MemberStatus.ACTIVE, MAX_MEMBERS, pageable);
        }
        if (supervisorId != null) {
            return studentGroupRepository.findBySupervisorId(supervisorId, pageable);
        }
        if (topicId != null) {
            return studentGroupRepository.findByTopicId(topicId, pageable);
        }
        return studentGroupRepository.findAll(pageable);
    }

    public List<GroupMember> listActiveMembers(UUID groupId) {
        return groupMemberRepository.findByGroupIdAndStatus(groupId, MemberStatus.ACTIVE);
    }

    @Transactional
    public StudentGroup update(UUID id, StudentGroupUpdateRequest request) {
        StudentGroup group = getById(id);
        if (request.topicId() != null) {
            group.setTopic(topicService.getById(request.topicId()));
        }
        if (request.supervisorId() != null) {
            group.setSupervisor(requireRole(request.supervisorId(), Role.INSTRUCTOR, Role.ADMIN));
        }
        group.setStatus(request.status());
        return group;
    }

    /**
     * Adds a member. Setting isLeader=true promotes that user's global role to GROUP_LEADER
     * (so RBAC checks elsewhere, e.g. slot booking in Sprint 2, work off the User.role claim);
     * a group may only have one active leader at a time — demote the current one first.
     */
    @Transactional
    public GroupMember addMember(UUID groupId, AddMemberRequest request) {
        StudentGroup group = getById(groupId);
        User user = userService.getById(request.userId());

        if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
            throw new BadRequestException("Only Student/Group Leader accounts can be added as group members");
        }
        if (groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, user.getId(), MemberStatus.ACTIVE)) {
            throw new ConflictException("User " + user.getEmail() + " is already an active member of this group");
        }
        if (groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE) >= MAX_MEMBERS) {
            throw new ConflictException("Group is full: a group can have at most " + MAX_MEMBERS + " members");
        }
        if (request.isLeader() && groupMemberRepository.existsByGroupIdAndIsLeaderTrueAndStatus(groupId, MemberStatus.ACTIVE)) {
            throw new ConflictException("This group already has an active leader; demote them before assigning a new one");
        }

        if (request.isLeader()) {
            user.setRole(Role.GROUP_LEADER);
        }

        GroupMember member = GroupMember.builder()
                .group(group)
                .user(user)
                .isLeader(request.isLeader())
                .joinedAt(Instant.now())
                .status(MemberStatus.ACTIVE)
                .build();
        return groupMemberRepository.save(member);
    }

    /** A student joins a group themselves; they must not already belong to one and the group must have room. */
    @Transactional
    public GroupMember join(UUID groupId, User current) {
        if (groupMemberRepository.existsByUserIdAndStatus(current.getId(), MemberStatus.ACTIVE)) {
            throw new ConflictException("You already belong to a group");
        }
        return addMember(groupId, new AddMemberRequest(current.getId(), false));
    }

    @Transactional
    public void removeMember(UUID groupId, UUID memberId) {
        GroupMember member = groupMemberRepository.findById(memberId)
                .filter(m -> m.getGroup().getId().equals(groupId))
                .orElseThrow(() -> ResourceNotFoundException.of("GroupMember", memberId));

        member.setStatus(MemberStatus.REMOVED);
        if (member.isLeader() && member.getUser().getRole() == Role.GROUP_LEADER) {
            member.getUser().setRole(Role.STUDENT);
        }
    }

    private User requireRole(UUID userId, Role... allowed) {
        User user = userService.getById(userId);
        for (Role role : allowed) {
            if (user.getRole() == role) {
                return user;
            }
        }
        throw new BadRequestException("User " + user.getEmail() + " does not have one of the required roles: " + List.of(allowed));
    }
}
