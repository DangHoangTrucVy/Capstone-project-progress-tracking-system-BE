package com.capstone.tracking.review;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserService;
import com.capstone.tracking.user.UserStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * A validated panel of lecturers (review panel or defense committee): distinct active Instructor/Council accounts,
 * with an optional chair who must be one of them.
 */
public record PanelSelection(List<User> members, UUID chairId) {

    public static PanelSelection resolve(UserService userService, List<UUID> memberIds, UUID chairId,
                                         Integer exactSize, boolean chairRequired) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(memberIds));
        if (ids.size() != memberIds.size()) {
            throw new BadRequestException("The same lecturer is listed twice");
        }
        if (exactSize != null && ids.size() != exactSize) {
            throw new BadRequestException("This panel needs exactly " + exactSize + " lecturers (got " + ids.size() + ")");
        }
        if (chairRequired && chairId == null) {
            throw new BadRequestException("A chair (chairId) is required for this panel");
        }
        if (chairId != null && !ids.contains(chairId)) {
            throw new BadRequestException("The chair must be one of the panel members");
        }
        List<User> members = ids.stream().map(userService::getById).toList();
        for (User u : members) {
            if (u.getRole() != Role.INSTRUCTOR && u.getRole() != Role.COUNCIL) {
                throw new BadRequestException(u.getEmail() + " is not an Instructor or Council member");
            }
            if (u.getStatus() != UserStatus.ACTIVE) {
                throw new BadRequestException(u.getEmail() + " is not an active account");
            }
        }
        return new PanelSelection(members, chairId);
    }

    public boolean isChair(User user) {
        return user.getId().equals(chairId);
    }

    public List<UUID> ids() {
        return members.stream().map(User::getId).toList();
    }

    public String describe() {
        return String.join(", ", members.stream()
                .map(u -> u.getFullName() + (isChair(u) ? " (Chủ tịch)" : ""))
                .toList());
    }
}
