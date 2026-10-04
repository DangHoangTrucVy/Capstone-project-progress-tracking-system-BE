package com.capstone.tracking.group;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * YC19/YC20: after Locked the supervisor reports roster changes (or a leader replacement) to the Admin, who is the
 * only one who edits the roster. The report is a request and a record: it changes nothing by itself.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RosterChangeReportService {

    private final RosterChangeReportRepository reports;
    private final StudentGroupService groups;
    private final ApplicationEventPublisher events;

    @Transactional
    public RosterChangeReport report(UUID groupId, User supervisor, RosterChangeReport.Type type, String description) {
        StudentGroup group = groups.getById(groupId);
        if (group.getSupervisor() == null || !group.getSupervisor().getId().equals(supervisor.getId())) {
            throw new AccessDeniedException("Only the group's supervisor can report a roster change");
        }
        if (description == null || description.isBlank()) {
            throw new BadRequestException("Describe the change the group needs");
        }
        RosterChangeReport saved = reports.save(RosterChangeReport.builder()
                .group(group)
                .reportedBy(supervisor)
                .type(type)
                .description(description.trim())
                .build());
        events.publishEvent(DomainEvent.of(DomainEventType.ROSTER_CHANGE_REPORTED, groupId, saved.getId(),
                supervisor.getId(), description.trim()));
        return saved;
    }

    @Transactional
    public RosterChangeReport resolve(UUID id, User admin, String note) {
        RosterChangeReport report = reports.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("RosterChangeReport", id));
        if (report.getStatus() == RosterChangeReport.Status.RESOLVED) {
            throw new ConflictException("This report is already resolved");
        }
        report.setStatus(RosterChangeReport.Status.RESOLVED);
        report.setResolvedBy(admin);
        report.setResolvedAt(Instant.now());
        report.setResolutionNote(note == null || note.isBlank() ? null : note.trim());
        return report;
    }

    /** Admin sees everything (optionally only one status). */
    public Page<RosterChangeReport> list(RosterChangeReport.Status status, Pageable pageable) {
        return status == null ? reports.findAll(pageable) : reports.findByStatus(status, pageable);
    }

    /** A supervisor sees the reports of their own groups; Admin sees any group's. */
    public List<RosterChangeReport> listForGroup(UUID groupId, User viewer) {
        if (viewer.getRole() != Role.ADMIN) {
            groups.requireSupervisorOrAdmin(groups.getById(groupId), viewer);
        }
        return reports.findByGroupIdOrderByCreatedAtDesc(groupId);
    }
}
