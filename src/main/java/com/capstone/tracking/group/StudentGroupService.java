package com.capstone.tracking.group;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.eligibility.EligibilityService;
import com.capstone.tracking.group.dto.AddMemberRequest;
import com.capstone.tracking.group.dto.StudentGroupCreateRequest;
import com.capstone.tracking.group.dto.StudentGroupUpdateRequest;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicService;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserService;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/**
 * FR-010 group/member management underpinning every later sprint (Booking, ArtifactSubmission, etc.
 * all key off StudentGroup / GroupMember per blueprint.md §8).
 *
 * <p>Joining goes through {@link #enroll}: a student becomes an official member when they create the group (Leader)
 * or Accept an Invite ({@link GroupJoinService}), or when an Admin adds them (YC11, YC19). Before the roster is
 * Locked the Leader may kick members; afterwards only an Admin changes it (YC18, YC19).</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentGroupService {

    /** YC06: a group is valid with 3-5 official members, Leader included. */
    public static final int MIN_MEMBERS = 3;
    public static final int MAX_MEMBERS = 5;

    private static final UUID NO_REQUEST = new UUID(0L, 0L);

    private final StudentGroupRepository studentGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupJoinRequestRepository joinRequestRepository;
    private final MemberLeaveRequestRepository leaveRequestRepository;
    private final TopicService topicService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final EligibilityService eligibilityService;
    private final ApplicationEventPublisher events;

    /**
     * A student creates a group and becomes its Leader and first official member (YC07); an Admin may also provision
     * an empty group (with topic/supervisor) and assign its roster through addMember.
     */
    @Transactional
    public StudentGroup create(StudentGroupCreateRequest request, User creator) {
        boolean admin = creator.getRole() == Role.ADMIN;
        if (!admin && creator.getRole() != Role.STUDENT && creator.getRole() != Role.GROUP_LEADER) {
            throw new AccessDeniedException("Only a student or an administrator can create a group");
        }
        String code = request.groupCode() == null || request.groupCode().isBlank()
                ? generateGroupCode() : request.groupCode().trim();
        if (studentGroupRepository.existsByGroupCodeIgnoreCase(code)) {
            throw new ConflictException("Group code " + code + " is already in use");
        }

        Topic topic = null;
        User supervisor = null;
        User leader = null;
        if (admin) {
            topic = request.topicId() != null ? topicService.getById(request.topicId()) : null;
            supervisor = request.supervisorId() != null
                    ? requireRole(request.supervisorId(), Role.INSTRUCTOR, Role.ADMIN)
                    : null;
        } else {
            if (request.topicId() != null || request.supervisorId() != null) {
                throw new BadRequestException("Only an administrator can set the topic or supervisor of a group");
            }
            leader = userRepository.lockById(creator.getId()).orElseThrow();
            eligibilityService.requireEligible(leader);
            if (groupMemberRepository.existsByUserIdAndStatus(leader.getId(), MemberStatus.ACTIVE)) {
                throw new ConflictException("You already belong to a group");
            }
        }

        StudentGroup saved = studentGroupRepository.save(StudentGroup.builder()
                .groupCode(code)
                .topic(topic)
                .supervisor(supervisor)
                .semester(request.semester())
                .status(GroupStatus.FORMED)
                .build());
        if (leader != null) {
            enroll(saved, leader, true, NO_REQUEST);
        }
        return saved;
    }

    private String generateGroupCode() {
        String code;
        do {
            code = "GRP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        } while (studentGroupRepository.existsByGroupCodeIgnoreCase(code));
        return code;
    }

    public StudentGroup getById(UUID id) {
        return studentGroupRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("StudentGroup", id));
    }

    /** Serialize changes to a group's bookings, submissions and roster inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StudentGroup lockById(UUID id) {
        return studentGroupRepository.lockById(id).orElseThrow(() -> ResourceNotFoundException.of("StudentGroup", id));
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

    /** availableOnly (groups still recruiting: fewer than MAX_MEMBERS, not locked) takes priority over the other filters. */
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

    public Page<StudentGroup> listForMember(UUID userId, Pageable pageable) {
        return studentGroupRepository.findForMember(userId, pageable);
    }

    @Transactional
    public StudentGroup update(UUID id, StudentGroupUpdateRequest request) {
        return update(id, request, null);
    }

    @Transactional
    public StudentGroup update(UUID id, StudentGroupUpdateRequest request, User actingUser) {
        if (actingUser != null && actingUser.getRole() == Role.GROUP_LEADER) {
            requireGroupLeader(id, actingUser);
        }
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
     * Admin adds a member (also the only way to change a Locked roster, YC19). Setting isLeader=true promotes that
     * user's global role to GROUP_LEADER; a group may only have one active leader at a time.
     */
    @Transactional
    public GroupMember addMember(UUID groupId, AddMemberRequest request) {
        return addMember(groupId, request, null);
    }

    @Transactional
    public GroupMember addMember(UUID groupId, AddMemberRequest request, User actingUser) {
        if (actingUser != null && actingUser.getRole() == Role.GROUP_LEADER) {
            requireGroupLeader(groupId, actingUser);
        }
        User resolved = resolveStudent(request.userId(), request.email(), request.identifier());
        // Same lock order as Accept Invite (student, then group) so the two can never deadlock.
        User user = userRepository.lockById(resolved.getId()).orElseThrow();
        StudentGroup group = lockById(groupId);
        eligibilityService.requireEligible(user);
        return enroll(group, user, request.isLeader(), NO_REQUEST);
    }

    /**
     * The single place where a student becomes an official member (YC13, YC15). The caller holds the locks on the
     * group row and the student row, so concurrent Accepts can neither exceed the group's capacity nor give one
     * student two memberships. Every other open Apply/Invite of the student is cancelled.
     *
     * @param acceptedRequestId the Invite being accepted (kept as ACCEPTED), or a null id when there is none
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GroupMember enroll(StudentGroup group, User user, boolean leader, UUID acceptedRequestId) {
        if (groupMemberRepository.existsByUserIdAndStatus(user.getId(), MemberStatus.ACTIVE)) {
            throw new ConflictException("This student already belongs to an active group");
        }
        if (user.getRole() != Role.STUDENT && user.getRole() != Role.GROUP_LEADER) {
            throw new BadRequestException("Only Student/Group Leader accounts can be added as group members");
        }
        if (groupMemberRepository.countByGroupIdAndStatus(group.getId(), MemberStatus.ACTIVE) >= MAX_MEMBERS) {
            throw new ConflictException("Group is full: a group can have at most " + MAX_MEMBERS + " members");
        }
        if (leader && groupMemberRepository.existsByGroupIdAndIsLeaderTrueAndStatus(group.getId(), MemberStatus.ACTIVE)) {
            throw new ConflictException("This group already has an active leader; demote them before assigning a new one");
        }

        if (leader) {
            user.setRole(Role.GROUP_LEADER);
        }
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(group.getId(), user.getId())
                .orElseGet(() -> GroupMember.builder().group(group).user(user).build());
        // A student who left or was kicked earlier may come back (YC21): the row is reused.
        member.setStatus(MemberStatus.ACTIVE);
        member.setLeader(leader);
        member.setJoinedAt(Instant.now());
        GroupMember saved = groupMemberRepository.save(member);

        invalidateRoster(group);
        joinRequestRepository.cancelOtherPending(user.getId(), acceptedRequestId == null ? NO_REQUEST : acceptedRequestId,
                Instant.now());
        return saved;
    }

    /**
     * Resolves a student by userId, email, student code (email prefix before '@') or a UUID string.
     */
    public User resolveStudent(UUID userId, String email, String identifier) {
        if (userId != null) {
            return userService.getById(userId);
        }

        String input = identifier != null && !identifier.isBlank()
                ? identifier.trim()
                : (email != null && !email.isBlank() ? email.trim() : null);

        if (input == null) {
            throw new BadRequestException("A valid user identifier (email, student code, or userId) must be provided");
        }

        // 1. Try finding by email
        Optional<User> byEmail = userRepository.findByEmailIgnoreCase(input);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }

        // 2. If input doesn't contain '@', try matching as student code (email prefix before '@')
        if (!input.contains("@")) {
            List<User> matchingPrefix = userRepository.findByEmailStartingWithIgnoreCase(input + "@");
            if (!matchingPrefix.isEmpty()) {
                return matchingPrefix.get(0);
            }
        }

        // 3. Try parsing as UUID if input might be a UUID string
        try {
            UUID id = UUID.fromString(input);
            return userService.getById(id);
        } catch (IllegalArgumentException ignored) {
        }

        throw new ResourceNotFoundException("Student with email or student code '" + input + "' not found");
    }

    /**
     * Removes a member. An Admin may always do it (YC19). The Leader may kick a member before the roster is Locked,
     * without any vote (YC18); the Leader cannot be kicked and nobody but an Admin touches a Locked roster.
     */
    @Transactional
    public void removeMember(UUID groupId, UUID memberId, User actingUser) {
        if (actingUser == null) {
            throw new AccessDeniedException("Only the group leader or an administrator can remove a member");
        }
        boolean admin = actingUser.getRole() == Role.ADMIN;
        if (!admin) {
            requireGroupLeader(groupId, actingUser);
        }
        StudentGroup group = lockById(groupId);
        GroupMember member = groupMemberRepository.findById(memberId)
                .filter(m -> m.getGroup().getId().equals(groupId) && m.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> ResourceNotFoundException.of("GroupMember", memberId));
        if (!admin) {
            requireNotLocked(group);
            if (member.isLeader()) {
                throw new BadRequestException("The leader cannot be kicked; ask an administrator to replace the leader");
            }
        }
        deactivate(group, member);
        events.publishEvent(DomainEvent.of(DomainEventType.MEMBER_REMOVED, groupId, member.getId(), actingUser.getId(),
                member.getUser().getFullName()).withTarget(member.getUser().getId()));
    }

    /** Used when a leave request is approved: the permission checks happened in the caller. */
    @Transactional
    public void removeActiveMember(UUID groupId, UUID userId) {
        StudentGroup group = lockById(groupId);
        GroupMember member = groupMemberRepository.findByGroupIdAndUserIdAndStatus(groupId, userId, MemberStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("This student is no longer an active member of the group"));
        deactivate(group, member);
    }

    private void deactivate(StudentGroup group, GroupMember member) {
        member.setStatus(MemberStatus.REMOVED);
        if (member.isLeader()) {
            member.setLeader(false);
        }
        if (member.getUser().getRole() == Role.GROUP_LEADER) {
            member.getUser().setRole(Role.STUDENT);
        }
        for (MemberLeaveRequest pending : leaveRequestRepository.findByGroupIdAndUserIdAndStatusIn(
                group.getId(), member.getUser().getId(), List.of(LeaveRequestStatus.PENDING))) {
            pending.setStatus(LeaveRequestStatus.WITHDRAWN);
            pending.setDecidedAt(Instant.now());
        }
        invalidateRoster(group);
    }

    /** YC20: Admin replaces (or assigns) the leader on behalf of the supervisor's report. */
    @Transactional
    public GroupMember replaceLeader(UUID groupId, UUID newLeaderUserId) {
        StudentGroup group = lockById(groupId);
        GroupMember next = groupMemberRepository.findByGroupIdAndUserIdAndStatus(groupId, newLeaderUserId, MemberStatus.ACTIVE)
                .orElseThrow(() -> new BadRequestException("The new leader must be an active member of the group"));
        if (next.isLeader()) {
            return next;
        }
        for (GroupMember current : groupMemberRepository.findByGroupIdAndStatus(groupId, MemberStatus.ACTIVE)) {
            if (current.isLeader()) {
                current.setLeader(false);
                if (current.getUser().getRole() == Role.GROUP_LEADER) {
                    current.getUser().setRole(Role.STUDENT);
                }
            }
        }
        next.setLeader(true);
        next.getUser().setRole(Role.GROUP_LEADER);
        invalidateRoster(group);
        return next;
    }

    /** YC19: Admin locks the roster; from then on only an Admin changes it. */
    @Transactional
    public StudentGroup setLocked(UUID groupId, boolean locked) {
        StudentGroup group = lockById(groupId);
        group.setLocked(locked);
        return group;
    }

    /** YC16: the Leader sends the member list (3-5 official members) to the group's supervisor. */
    @Transactional
    public StudentGroup submitRoster(UUID groupId, User actingUser) {
        StudentGroup group = lockById(groupId);
        requireGroupLeader(groupId, actingUser);
        requireNotLocked(group);
        long members = groupMemberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE);
        if (members < MIN_MEMBERS || members > MAX_MEMBERS) {
            throw new ConflictException("A group needs " + MIN_MEMBERS + "-" + MAX_MEMBERS
                    + " official members before its roster can be submitted (currently " + members + ")");
        }
        if (group.getSupervisor() == null) {
            throw new ConflictException("The group has no supervisor yet; the roster cannot be sent for approval");
        }
        if (group.getRosterStatus() == RosterStatus.SUBMITTED || group.getRosterStatus() == RosterStatus.APPROVED) {
            throw new ConflictException("The roster is already " + group.getRosterStatus().name().toLowerCase());
        }
        group.setRosterStatus(RosterStatus.SUBMITTED);
        group.setRosterNote(null);
        events.publishEvent(DomainEvent.of(DomainEventType.ROSTER_SUBMITTED, groupId, groupId, actingUser.getId(),
                group.getGroupCode()));
        return group;
    }

    /**
     * YC16: the group's supervisor (or an Admin) approves or rejects the submitted roster. A rejection returns the
     * roster to the leader with the note; what else it triggers is still open (GV02).
     */
    @Transactional
    public StudentGroup reviewRoster(UUID groupId, User actingUser, boolean approved, String note) {
        StudentGroup group = lockById(groupId);
        requireSupervisorOrAdmin(group, actingUser);
        if (group.getRosterStatus() != RosterStatus.SUBMITTED) {
            throw new ConflictException("There is no submitted roster to review");
        }
        if (!approved && (note == null || note.isBlank())) {
            throw new BadRequestException("Say why the roster is rejected");
        }
        group.setRosterStatus(approved ? RosterStatus.APPROVED : RosterStatus.REJECTED);
        group.setRosterNote(note == null || note.isBlank() ? null : note.trim());
        events.publishEvent(DomainEvent.of(DomainEventType.ROSTER_REVIEWED, groupId, groupId, actingUser.getId(),
                approved ? "đã được duyệt" : "bị từ chối").withDetails(group.getRosterNote(), null));
        return group;
    }

    /** Any roster change makes an earlier submission/approval stale; the leader must submit again. */
    private void invalidateRoster(StudentGroup group) {
        if (group.getRosterStatus() == RosterStatus.SUBMITTED || group.getRosterStatus() == RosterStatus.APPROVED) {
            group.setRosterStatus(RosterStatus.DRAFT);
            group.setRosterNote(null);
        }
    }

    /** Only the group's active leader may act for the group (submit topics, book slots...). */
    public void requireActiveLeader(UUID groupId, User actingUser) {
        requireGroupLeader(groupId, actingUser);
    }

    public boolean isActiveMember(UUID groupId, UUID userId) {
        return groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, userId, MemberStatus.ACTIVE);
    }

    /** Applying, inviting and joining only make sense while the group is running and its roster is not Locked. */
    public void requireRecruiting(StudentGroup group) {
        if (group.getStatus() == GroupStatus.COMPLETED || group.getStatus() == GroupStatus.FAILED
                || group.getStatus() == GroupStatus.ARCHIVED) {
            throw new ConflictException("Cannot join a group that is " + group.getStatus().name().toLowerCase());
        }
        requireNotLocked(group);
    }

    public void requireNotLocked(StudentGroup group) {
        if (group.isLocked()) {
            throw new ConflictException(
                    "The group roster is locked; changes go through the supervisor and an administrator");
        }
    }

    /** Students and leaders may only see their own group; staff (Instructor, Council, Admin) see every group. */
    public void requireCanView(UUID groupId, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }

    /** The group's own supervisor, or an Admin. */
    public void requireSupervisorOrAdmin(StudentGroup group, User actingUser) {
        if (actingUser.getRole() == Role.ADMIN) {
            return;
        }
        if (group.getSupervisor() == null || !group.getSupervisor().getId().equals(actingUser.getId())) {
            throw new AccessDeniedException("Only the group's supervisor can do this");
        }
    }

    private void requireGroupLeader(UUID groupId, User actingUser) {
        if (!groupMemberRepository.existsByGroupIdAndUserIdAndIsLeaderTrueAndStatus(groupId, actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not the leader of this group");
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
