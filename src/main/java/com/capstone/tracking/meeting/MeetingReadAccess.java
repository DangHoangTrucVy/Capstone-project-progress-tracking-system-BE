package com.capstone.tracking.meeting;

import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Access guard for reading meeting sessions, minutes, and requirement logs (Issue #43).
 * Allowed readers:
 * - ADMIN: full read access.
 * - INSTRUCTOR: the booked slot instructor or the group's current supervisor.
 * - GROUP_LEADER / STUDENT: active members of the booked group.
 * COUNCIL and unrelated instructors/students are denied access.
 */
@Component
@RequiredArgsConstructor
public class MeetingReadAccess {

    private final GroupMemberRepository groupMemberRepository;

    public void requireCanRead(MeetingSession session, User user) {
        if (session == null || session.getBooking() == null) {
            throw new AccessDeniedException("Session booking information is missing");
        }
        requireCanRead(session.getBooking(), user);
    }

    public void requireCanRead(Booking booking, User user) {
        if (user == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (user.getRole() == Role.ADMIN) {
            return;
        }
        if (user.getRole() == Role.GROUP_LEADER || user.getRole() == Role.STUDENT) {
            UUID groupId = booking.getGroup() != null ? booking.getGroup().getId() : null;
            if (groupId == null || !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, user.getId(), MemberStatus.ACTIVE)) {
                throw new AccessDeniedException("You are not an active member of this group");
            }
            return;
        }
        if (user.getRole() == Role.INSTRUCTOR) {
            boolean isSlotInstructor = booking.getSlot() != null
                    && booking.getSlot().getInstructor() != null
                    && booking.getSlot().getInstructor().getId().equals(user.getId());
            boolean isSupervisor = booking.getGroup() != null
                    && booking.getGroup().getSupervisor() != null
                    && booking.getGroup().getSupervisor().getId().equals(user.getId());
            if (isSlotInstructor || isSupervisor) {
                return;
            }
            throw new AccessDeniedException("You are not the booked instructor or supervisor for this meeting");
        }
        throw new AccessDeniedException("This role cannot view meeting details");
    }
}
