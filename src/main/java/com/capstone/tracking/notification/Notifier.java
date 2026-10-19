package com.capstone.tracking.notification;

import com.capstone.tracking.semester.Semester;
import com.capstone.tracking.semester.SemesterRepository;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import com.capstone.tracking.user.UserRepository;
import com.capstone.tracking.user.UserStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** Publishes {@link DomainEvent}s and answers "who coordinates this semester" for the recipients. */
@Component
@RequiredArgsConstructor
public class Notifier {

    private final ApplicationEventPublisher events;
    private final SemesterRepository semesters;
    private final UserRepository users;

    public void publish(DomainEvent event) {
        if (event.recipients() != null && !event.recipients().isEmpty()) {
            events.publishEvent(event);
        }
    }

    /** The semester's Moderator, or every active Admin while none is assigned (they do the Moderator's work then). */
    public List<UUID> coordinators(String semesterCode) {
        UUID moderator = semesters.findById(semesterCode).map(Semester::getModeratorId).orElse(null);
        return moderator != null ? List.of(moderator) : admins();
    }

    public List<UUID> admins() {
        return users.findByRole(Role.ADMIN, Pageable.unpaged()).stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .map(User::getId)
                .toList();
    }
}
