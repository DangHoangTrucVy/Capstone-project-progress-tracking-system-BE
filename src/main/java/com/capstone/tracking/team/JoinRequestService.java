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
import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterGuard;
import com.capstone.tracking.semester.SemesterPhase;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests and invites (6.2, 7.4).
 * <ul>
 *   <li>APPLY: a student asks to join a room its Leader locked; the Leader accepting it makes the student a member at
 *       once. At most the semester's {@code maxPendingRequests} at a time; each lives {@code requestTtlMinutes}.</li>
 *   <li>INVITE: a Leader invites a student of the same major without a room, before the deadline (with the same
 *       lifetime) or in the supplement phase (no lifetime; only rooms below the maximum size invite). Accepting one
 *       cancels the student's other invites; in the supplement phase the Moderator is told.</li>
 * </ul>
 * Neither holds a seat, so capacity and "no room yet" are checked again on acceptance under the locks (semester shared,
 * the student's enrollment, the room).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JoinRequestService {

    private final JoinRequestRepository requests;
    private final ApplicationLikeRepository likes;
    private final EnrollmentRepository enrollments;
    private final UserRepository users;
    private final GroupCore core;
    private final StudentGate gate;
    private final SemesterGuard guard;
    private final Access access;
    private final Notifier notifier;
    private final Locks locks;

    // ------------------------------------------------------------------ request to join a locked room

    /** Called by "join" on a locked room, under its locks (semester shared, enrollment, room). */
    @Transactional(propagation = Propagation.MANDATORY)
    JoinRequest createApply(StudentGroup group, Enrollment enrollment, String message, Semester semester, User user,
                            Instant now) {
        clearDeadDuplicate(group.getId(), enrollment.getId(), Type.APPLY, now, "You already asked to join this room");
        if (requests.countOpenApplies(enrollment.getId(), now) >= semester.getMaxPendingRequests()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_REQUESTS",
                    "You can have at most " + semester.getMaxPendingRequests()
                            + " pending requests; withdraw one or wait for an answer");
        }
        JoinRequest saved = requests.saveAndFlush(JoinRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .student(enrollment.getUser())
                .type(Type.APPLY)
                .message(GroupService.blankToNull(message))
                .createdBy(user.getId())
                .expiresAt(now.plus(Duration.ofMinutes(semester.getRequestTtlMinutes())))
                .build());
        GroupMember leader = core.leaderOf(group.getId());
        notifier.publish(DomainEvent.of(DomainEventType.APPLICATION_RECEIVED,
                user.getFullName() + " (" + user.getStudentCode() + ") xin vào " + GroupCore.label(group),
                leader == null ? null : leader.getUser().getId()).about(group.getId(), saved.getId()).by(user.getId()));
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void cancelApplies(StudentGroup group, JoinRequest.CancelReason reason) {
        requests.cancelPendingOfGroup(group.getId(), Type.APPLY, reason, Instant.now());
    }

    // ------------------------------------------------------------------ Invite

    /**
     * The Leader invites a student of the same major who has no room: before the deadline, or in the supplement phase
     * (7.4) where the invite never expires and only rooms below the maximum size invite. Several rooms may invite the
     * same student.
     */
    @Transactional
    public JoinRequest invite(UUID groupId, UUID studentUserId, String studentCode, String message, User user) {
        StudentGroup group = core.get(groupId);
        Semester semester = locks.readSemester(group.getScope().semesterCode());
        boolean supplement = semester.getPhase() == SemesterPhase.SUPPLEMENT;
        if (!supplement) {
            guard.requireBeforeDeadline(semester);
        }
        gate.requireCanAct(semester.getCode(), user);
        User target = resolve(studentUserId, studentCode);
        Enrollment enrollment = enrollments.findBySemesterAndUser(semester.getCode(), target.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_ENROLLED",
                        "This student is not on the list of this semester"));
        enrollment = core.lockEnrollment(enrollment.getId());
        group = core.lock(groupId);
        core.requireLeader(group, user.getId());
        if (!supplement) {
            GroupService.requireNotApproved(group);
        }
        requireSameScope(enrollment, group);
        gate.requireEligible(enrollment);
        requireNoGroup(enrollment, "This student already belongs to a room");
        requireSeat(group, semester);
        Instant now = Instant.now();
        clearDeadDuplicate(groupId, enrollment.getId(), Type.INVITE, now,
                "This student already has a pending invitation from your room");
        JoinRequest saved = requests.saveAndFlush(JoinRequest.builder()
                .group(group)
                .enrollment(enrollment)
                .student(target)
                .type(Type.INVITE)
                .message(GroupService.blankToNull(message))
                .createdBy(user.getId())
                .expiresAt(supplement ? null : now.plus(Duration.ofMinutes(semester.getRequestTtlMinutes())))
                .build());
        notifier.publish(DomainEvent.of(DomainEventType.INVITE_RECEIVED,
                GroupCore.label(group) + " mời bạn tham gia", target.getId()).about(groupId, saved.getId()).by(user.getId()));
        return saved;
    }

    // ------------------------------------------------------------------ answers

    /** The Leader accepts a request to join, or the invited student accepts an invite: the student joins now. */
    @Transactional
    public GroupMember accept(UUID requestId, User user) {
        JoinRequest snapshot = find(requestId);
        Semester semester = locks.readSemester(snapshot.getGroup().getScope().semesterCode());
        Enrollment enrollment = core.lockEnrollment(snapshot.getEnrollment().getId());
        JoinRequest request = lock(requestId);
        StudentGroup group = core.lock(request.getGroup().getId());
        if (request.getType() == Type.APPLY) {
            core.requireLeader(group, user.getId());
            gate.requireCanAct(semester.getCode(), user);
        } else if (!request.getStudent().getId().equals(user.getId())) {
            throw new AccessDeniedException("This invitation is not addressed to you");
        }
        requirePending(request);
        boolean supplement = request.getType() == Type.INVITE && semester.getPhase() == SemesterPhase.SUPPLEMENT;
        if (!supplement) {
            guard.requireBeforeDeadline(semester);
            GroupService.requireNotApproved(group);
            GroupService.requireNoFinalizeRequest(group);
        }
        gate.requireCanAct(enrollment);
        Instant now = Instant.now();
        GroupMember member = core.enroll(group, enrollment, false, semester, now);
        request.setStatus(Status.ACCEPTED);
        request.setRespondedAt(now);
        if (request.getType() == Type.APPLY) {
            notifier.publish(DomainEvent.of(DomainEventType.APPLICATION_ACCEPTED,
                    "Bạn đã được nhận vào " + GroupCore.label(group), request.getStudent().getId())
                    .about(group.getId(), request.getId()).by(user.getId()));
        } else {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.INVITE_ACCEPTED,
                    user.getFullName() + " đã đồng ý vào " + GroupCore.label(group),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId()).by(user.getId()));
        }
        if (supplement) {
            notifier.publish(DomainEvent.of(DomainEventType.SUPPLEMENT_JOINED,
                    user.getFullName() + " (" + user.getStudentCode() + ") đã nhận lời mời và vào " + GroupCore.label(group),
                    notifier.coordinators(semester.getCode())).about(group.getId(), request.getId()).by(user.getId()));
        }
        notifier.publish(DomainEvent.of(DomainEventType.MEMBER_JOINED,
                request.getStudent().getFullName() + " đã vào " + GroupCore.label(group),
                core.activeMembers(group.getId()).stream().map(m -> m.getUser().getId())
                        .filter(id -> !id.equals(request.getStudent().getId())).toList())
                .about(group.getId(), null).by(user.getId()));
        return member;
    }

    /** The Leader turns a request down (with a reason for the student), or the student declines an invite. */
    @Transactional
    public JoinRequest reject(UUID requestId, String reason, User user) {
        JoinRequest snapshot = find(requestId);
        Semester semester = locks.readSemester(snapshot.getGroup().getScope().semesterCode());
        guard.requireNotClosed(semester);
        JoinRequest request = lock(requestId);
        StudentGroup group = request.getGroup();
        if (request.getType() == Type.APPLY) {
            gate.requireCanAct(semester.getCode(), user);
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
                    GroupCore.label(group) + " đã từ chối yêu cầu vào nhóm của bạn", request.getStudent().getId())
                    .about(group.getId(), request.getId()).withDetails(request.getRejectReason(), null).by(user.getId()));
        } else {
            GroupMember leader = core.leaderOf(group.getId());
            notifier.publish(DomainEvent.of(DomainEventType.INVITE_DECLINED,
                    user.getFullName() + " đã từ chối lời mời vào " + GroupCore.label(group),
                    leader == null ? null : leader.getUser().getId()).about(group.getId(), request.getId()).by(user.getId()));
        }
        return request;
    }

    /** The sender takes it back before it is answered: the student a request, the Leader an invite. */
    @Transactional
    public JoinRequest withdraw(UUID requestId, User user) {
        JoinRequest snapshot = find(requestId);
        Semester semester = locks.readSemester(snapshot.getGroup().getScope().semesterCode());
        guard.requireNotClosed(semester);
        gate.requireCanAct(semester.getCode(), user);
        JoinRequest request = lock(requestId);
        if (request.getType() == Type.APPLY) {
            if (!request.getStudent().getId().equals(user.getId())) {
                throw new AccessDeniedException("This is not your request");
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
        Semester semester = locks.readSemester(request.getGroup().getScope().semesterCode());
        guard.requireBeforeDeadline(semester);
        gate.requireCanAct(semester.getCode(), user);
        if (request.getType() != Type.APPLY) {
            throw new BadRequestException("Only requests to join can be liked");
        }
        if (!core.isMember(request.getGroup().getId(), user.getId())) {
            throw new AccessDeniedException("Only members of the room can like a candidate");
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

    /** The room's requests and invites, for its members and the Moderator. */
    public List<JoinRequestResponse> forGroup(UUID groupId, User viewer) {
        StudentGroup group = core.get(groupId);
        boolean staff = access.isModerator(group.getScope().semesterCode(), viewer);
        if (!staff && !core.isMember(groupId, viewer.getId())) {
            throw new AccessDeniedException("You are not a member of this room");
        }
        return requests.findByGroupIdOrderByCreatedAtDesc(groupId).stream().map(r -> toResponse(r, viewer, staff)).toList();
    }

    /** The student's own requests and invites in a semester. */
    public List<JoinRequestResponse> mine(String semesterCode, User viewer) {
        Enrollment enrollment = gate.enrollment(semesterCode, viewer);
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
        // 8.1: the room sees the student's whole profile while the request is pending, then that right ends.
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

    /** One major per room (3.3). */
    private static void requireSameScope(Enrollment enrollment, StudentGroup group) {
        if (!enrollment.getScope().getId().equals(group.getScope().getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "OTHER_MAJOR", "Rooms are formed within one major only");
        }
    }

    private void requireNoGroup(Enrollment enrollment, String message) {
        if (core.groupOf(enrollment.getId()) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_GROUP", message);
        }
    }

    private void requireSeat(StudentGroup group, Semester semester) {
        int max = semester.getMaxGroupSize();
        if (core.size(group.getId()) >= max) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_FULL", "The room is full (" + max + " members)");
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
            if (existing.effectiveStatus(now) == Status.PENDING) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", conflict);
            }
            existing.setStatus(Status.EXPIRED);
            existing.setRespondedAt(now);
            requests.saveAndFlush(existing);
        });
    }
}
