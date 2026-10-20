package com.capstone.tracking.team;

import com.capstone.tracking.common.Locks;
import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.notification.Notifier;
import com.capstone.tracking.roster.Enrollment;
import com.capstone.tracking.roster.EnrollmentRepository;
import com.capstone.tracking.roster.StudentGate;
import com.capstone.tracking.semester.Access;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.ScopeRules;
import com.capstone.tracking.team.JoinRequest.Status;
import com.capstone.tracking.team.JoinRequest.Type;
import com.capstone.tracking.team.dto.JoinRequestResponse;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.dto.ProfileView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Apply and Invite (6.2). The Leader accepting an Apply, or the student accepting an Invite, makes the student a
 * member at once; neither request holds a seat, so capacity and "no group yet" are checked again at that moment under
 * the locks (scope shared, then the student's enrollment, then the group). A student has at most 3 open Applies;
 * Invites are unlimited. Requests live {@code requestTtlHours} (48 h) and close at the deadline.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JoinRequestService {

    public static final int MAX_OPEN_APPLIES = 3;

    private final JoinRequestRepository requests;
    private final ApplicationLikeRepository likes;
    private final EnrollmentRepository enrollments;
    private final FormationScopeRepository scopes;
    private final UserRepository users;
    private final GroupCore core;
    private final StudentGate gate;
    private final ScopeRules rules;
    private final Access access;
    private final Notifier notifier;
    private final Locks locks;

    // ------------------------------------------------------------------ Apply

    @Transactional
    public JoinRequest apply(UUID groupId, String message, User user) {
        StudentGroup group = core.get(groupId);
        Enrollment enrollment = gate.requireCanAct(group.getScope().getSubject().getId(), user);
        requireSameScope(enrollment, group);
        FormationScope scope = lockScopeShared(group.getScope().getId());
        rules.requireOpen(scope);
        enrollment = core.lockEnrollment(enrollment.getId());
        group = core.lock(groupId);
        requireNoGroup(enrollment, "You already belong to a group in this subject");
        if (group.isLocked()) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_LOCKED", "The group is locked and takes no new applications");
        }
        requireRoom(group);
        Instant now = Instant.now();
        clearDeadDuplicate(groupId, enrollment.getId(), Type.APPLY, now, "You already applied to this group");
        if (requests.countOpenApplies(enrollment.getId(), now) >= MAX_OPEN_APPLIES) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_APPLIES",
                    "You can have at most " + MAX_OPEN_APPLIES + " pending applications; withdraw one or wait for an answer");
        }
        JoinRequest saved = requests.saveAndFlush(JoinRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .student(enrollment.getUser())
                .type(Type.APPLY)
                .message(GroupService.blankToNull(message))
                .createdBy(user.getId())
                .expiresAt(expiry(group, now))
                .build());
        GroupMember leader = core.leaderOf(groupId);
        notifier.publish(DomainEvent.of(DomainEventType.APPLICATION_RECEIVED,
                user.getFullName() + " (" + user.getStudentCode() + ") xin vào nhóm " + group.getName(),
                leader == null ? null : leader.getUser().getId()).about(groupId, saved.getId()).by(user.getId()));
        return saved;
    }

    // ------------------------------------------------------------------ Invite

    /** The Leader invites a student of the same scope who has no group; allowed while the group is locked (6.3). */
    @Transactional
    public JoinRequest invite(UUID groupId, UUID studentUserId, String studentCode, String message, User user) {
        StudentGroup group = core.get(groupId);
        FormationScope scope = lockScopeShared(group.getScope().getId());
        rules.requireOpen(scope);
        gate.requireCanAct(group.getScope().getSubject().getId(), user);
        User target = resolve(studentUserId, studentCode);
        Enrollment enrollment = enrollments.findBySubjectIdAndUserId(scope.getSubject().getId(), target.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ENROLLED",
                        "This student is not on the list of this subject"));
        enrollment = core.lockEnrollment(enrollment.getId());
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        requireSameScope(enrollment, group);
        gate.requireEligible(enrollment);
        requireNoGroup(enrollment, "This student already belongs to a group");
        requireRoom(group);
        Instant now = Instant.now();
        clearDeadDuplicate(groupId, enrollment.getId(), Type.INVITE, now,
                "This student already has a pending invitation from your group");
        JoinRequest saved = requests.saveAndFlush(JoinRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .student(target)
                .type(Type.INVITE)
                .message(GroupService.blankToNull(message))
                .createdBy(user.getId())
                .expiresAt(expiry(group, now))
                .build());
        notifier.publish(DomainEvent.of(DomainEventType.INVITE_RECEIVED,
                "Nhóm " + group.getName() + " mời bạn tham gia", target.getId()).about(groupId, saved.getId()).by(user.getId()));
        return saved;
    }

    // ------------------------------------------------------------------ answers

    /** The Leader accepts an Apply, or the invited student accepts an Invite: the student joins now. */
    @Transactional
    public GroupMember accept(UUID requestId, User user) {
        JoinRequest snapshot = find(requestId);
        FormationScope scope = lockScopeShared(snapshot.getGroup().getScope().getId());
        Enrollment enrollment = core.lockEnrollment(snapshot.getEnrollment().getId());
        JoinRequest request = lock(requestId);
        StudentGroup group = core.lock(request.getGroup().getId());
        if (request.getType() == Type.APPLY) {
            core.requireLeader(group, user.getId());
            gate.requireCanAct(scope.getSubject().getId(), user);
        } else if (!request.getStudent().getId().equals(user.getId())) {
            throw new AccessDeniedException("This invitation is not addressed to you");
        }
        requirePending(request);
        rules.requireOpen(scope);
        gate.requireCanAct(enrollment);
        Instant now = Instant.now();
        GroupMember member = core.enroll(group, enrollment, false, now);
        request.setStatus(Status.ACCEPTED);
        request.setRespondedAt(now);
        if (request.getType() == Type.APPLY) {
            notifier.publish(DomainEvent.of(DomainEventType.APPLICATION_ACCEPTED,
                    "Bạn đã được nhận vào nhóm " + group.getName(), request.getStudent().getId())
                    .about(group.getId(), request.getId()).by(user.getId()));
        } else {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.INVITE_ACCEPTED,
                    user.getFullName() + " đã đồng ý vào nhóm " + group.getName(),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId()).by(user.getId()));
        }
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_JOINED,
                request.getStudent().getFullName() + " đã vào nhóm " + group.getName(),
                core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId()).toList())
                .about(group.getId(), null).by(user.getId()));
        return member;
    }

    /** The Leader turns an Apply down (with a reason for the applicant), or the student declines an Invite. */
    @Transactional
    public JoinRequest reject(UUID requestId, String reason, User user) {
        JoinRequest snapshot = find(requestId);
        FormationScope scope = lockScopeShared(snapshot.getGroup().getScope().getId());
        rules.requireOpen(scope);
        gate.requireCanAct(scope.getSubject().getId(), user);
        JoinRequest request = lock(requestId);
        StudentGroup group = request.getGroup();
        if (request.getType() == Type.APPLY) {
            core.requireLeader(group, user.getId());
        } else if (!request.getStudent().getId().equals(user.getId())) {
            throw new AccessDeniedException("This invitation is not addressed to you");
        }
        requirePending(request);
        request.setStatus(Status.REJECTED);
        request.setRespondedAt(Instant.now());
        if (request.getType() == Type.APPLY) {
            request.setRejectReason(GroupService.blankToNull(reason));
            notifier.publish(DomainEvent.of(DomainEventType.APPLICATION_REJECTED,
                    "Nhóm " + group.getName() + " đã từ chối đơn của bạn", request.getStudent().getId())
                    .about(group.getId(), request.getId()).withDetails(request.getRejectReason(), null).by(user.getId()));
        } else {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.INVITE_DECLINED,
                    user.getFullName() + " đã từ chối lời mời vào nhóm " + group.getName(),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId()).by(user.getId()));
        }
        return request;
    }

    /** The sender takes the request back before it is answered: the applicant an Apply, the Leader an Invite. */
    @Transactional
    public JoinRequest withdraw(UUID requestId, User user) {
        JoinRequest snapshot = find(requestId);
        FormationScope scope = lockScopeShared(snapshot.getGroup().getScope().getId());
        rules.requireOpen(scope);
        gate.requireCanAct(scope.getSubject().getId(), user);
        JoinRequest request = lock(requestId);
        if (request.getType() == Type.APPLY) {
            if (!request.getStudent().getId().equals(user.getId())) {
                throw new AccessDeniedException("This is not your application");
            }
        } else {
            core.requireLeader(request.getGroup(), user.getId());
        }
        requirePending(request);
        request.setStatus(Status.WITHDRAWN);
        request.setRespondedAt(Instant.now());
        return request;
    }

    // ------------------------------------------------------------------ Likes (8.2)

    @Transactional
    public long like(UUID requestId, boolean liked, User user) {
        JoinRequest request = find(requestId);
        FormationScope scope = lockScopeShared(request.getGroup().getScope().getId());
        rules.requireOpen(scope);
        gate.requireCanAct(scope.getSubject().getId(), user);
        if (request.getType() != Type.APPLY) {
            throw new BadRequestException("Only applications can be liked");
        }
        if (!core.isMember(request.getGroup().getId(), user.getId())) {
            throw new AccessDeniedException("Only members of the group can like an applicant");
        }
        requirePending(request);
        var existing = likes.findByRequestIdAndUserId(requestId, user.getId());
        if (liked && existing.isEmpty()) {
            likes.save(ApplicationLike.builder().requestId(requestId).userId(user.getId()).build());
        } else if (!liked) {
            existing.ifPresent(likes::delete);
        }
        likes.flush();
        return likes.countByRequestId(requestId);
    }

    // ------------------------------------------------------------------ queries

    /** The group's requests, for its members and the staff. */
    public List<JoinRequestResponse> forGroup(UUID groupId, User viewer) {
        StudentGroup group = core.get(groupId);
        boolean staff = access.isModerator(group.getScope().semesterCode(), viewer);
        if (!staff && !core.isMember(groupId, viewer.getId())) {
            throw new AccessDeniedException("You are not a member of this group");
        }
        return requests.findByGroupIdOrderByCreatedAtDesc(groupId).stream().map(r -> toResponse(r, viewer, staff)).toList();
    }

    /** The student's own Applies and Invites in a subject. */
    public List<JoinRequestResponse> mine(UUID subjectId, User viewer) {
        Enrollment enrollment = gate.enrollment(subjectId, viewer);
        return requests.findByEnrollment(enrollment.getId()).stream().map(r -> toResponse(r, viewer, false)).toList();
    }

    public JoinRequestResponse get(UUID requestId, User viewer) {
        JoinRequest request = find(requestId);
        boolean staff = access.isModerator(request.getGroup().getScope().semesterCode(), viewer);
        boolean involved = request.getStudent().getId().equals(viewer.getId())
                || core.isMember(request.getGroup().getId(), viewer.getId());
        if (!staff && !involved) {
            throw new AccessDeniedException("This request does not concern you");
        }
        return toResponse(request, viewer, staff);
    }

    public JoinRequestResponse toResponse(JoinRequest r, User viewer, boolean staff) {
        Instant now = Instant.now();
        Status status = r.effectiveStatus(now);
        boolean self = r.getStudent().getId().equals(viewer.getId());
        boolean member = !self && core.isMember(r.getGroup().getId(), viewer.getId());
        // 8.1: the group sees the applicant's whole profile while the request is pending, then that right ends.
        boolean teammate = member && core.isMember(r.getGroup().getId(), r.getStudent().getId());
        boolean full = self || staff || teammate || (member && r.getType() == Type.APPLY && status == Status.PENDING);
        Long likeCount = null;
        Boolean likedByMe = null;
        if (r.getType() == Type.APPLY && (member || staff)) {
            likeCount = likes.countByRequestId(r.getId());
            likedByMe = likes.findByRequestIdAndUserId(r.getId(), viewer.getId()).isPresent();
        }
        return new JoinRequestResponse(r.getId(), r.getType(), status, r.getGroup().getId(), r.getGroup().getCode(),
                r.getGroup().getName(), ProfileView.visible(r.getStudent(), full), r.getMessage(), r.getRejectReason(),
                r.getCancelReason(), r.getCreatedBy(), r.getCreatedAt(), r.getExpiresAt(), r.getRespondedAt(),
                likeCount, likedByMe);
    }

    // ------------------------------------------------------------------ helpers

    private JoinRequest find(UUID requestId) {
        return requests.findById(requestId).orElseThrow(() -> ResourceNotFoundException.of("Request", requestId));
    }

    private JoinRequest lock(UUID requestId) {
        return locks.write(JoinRequest.class, requestId);
    }

    private FormationScope lockScopeShared(UUID scopeId) {
        return locks.read(FormationScope.class, scopeId);
    }

    private User resolve(UUID userId, String studentCode) {
        if (userId != null) {
            return users.findById(userId).orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        }
        if (studentCode != null && !studentCode.isBlank()) {
            return users.findByStudentCodeIgnoreCase(studentCode.trim())
                    .orElseThrow(() -> ResourceNotFoundException.of("Student", studentCode));
        }
        throw new BadRequestException("Give the student's id or student code");
    }

    /** One group per major: a group only takes students of its own scope (3.3, 6.1). */
    private static void requireSameScope(Enrollment enrollment, StudentGroup group) {
        if (!enrollment.getScope().getId().equals(group.getScope().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "Groups are formed within one major only");
        }
    }

    private void requireNoGroup(Enrollment enrollment, String message) {
        if (core.groupOf(enrollment.getId()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP", message);
        }
    }

    private void requireRoom(StudentGroup group) {
        int max = group.getScope().getSubject().getMaxGroupSize();
        if (core.size(group.getId()) >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The group is full (" + max + " members)");
        }
    }

    private void requirePending(JoinRequest request) {
        Status status = request.effectiveStatus(Instant.now());
        if (status != Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CLOSED",
                    "This " + request.getType().name().toLowerCase() + " is already " + status.name().toLowerCase());
        }
    }

    /** An open request of the same kind blocks a new one; a dead (expired, not yet swept) one is written down. */
    private void clearDeadDuplicate(UUID groupId, UUID enrollmentId, Type type, Instant now, String conflict) {
        requests.findPending(groupId, enrollmentId, type).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", conflict);
            }
            existing.setStatus(Status.EXPIRED);
            existing.setRespondedAt(now);
            requests.saveAndFlush(existing);
        });
    }

    private static Instant expiry(StudentGroup group, Instant now) {
        return now.plus(Duration.ofHours(group.getScope().getSubject().getRequestTtlHours()));
    }
}
