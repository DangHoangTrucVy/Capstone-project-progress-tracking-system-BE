package com.capstone.tracking.meeting;

import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Meeting writes follow the lecturer booked for this slot, even if the group has another supervisor. */
@Component
@RequiredArgsConstructor
public class MeetingWriteAccess {
    private final StudentGroupService groups;

    public void requireParticipant(Booking booking, User user) {
        if (user.getRole() == Role.GROUP_LEADER) {
            groups.requireActiveLeader(booking.getGroup().getId(), user);
            return;
        }
        requireSlotInstructor(booking, user);
    }

    /** Admin's existing override applies only to approving/rejecting minutes. */
    public void requireMinuteApprover(Booking booking, User user) {
        if (user.getRole() != Role.ADMIN) {
            requireSlotInstructor(booking, user);
        }
    }

    private void requireSlotInstructor(Booking booking, User user) {
        if (user.getRole() != Role.INSTRUCTOR
                || !booking.getSlot().getInstructor().getId().equals(user.getId())) {
            throw new AccessDeniedException("Only the booked slot's instructor can do this");
        }
    }
}
