package com.capstone.tracking.scheduling;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.scheduling.dto.SlotCreateRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sprint 2 — API-001 / API-002. See blueprint.md §5 step 1 for the no-overlap rule this enforces. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleSlotService {

    private final ScheduleSlotRepository scheduleSlotRepository;

    @Transactional
    public ScheduleSlot create(SlotCreateRequest request, User instructor) {
        if (instructor.getRole() != Role.INSTRUCTOR && instructor.getRole() != Role.ADMIN) {
            throw new BadRequestException("Only Instructor/Admin accounts can publish assessment slots");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BadRequestException("endTime must be after startTime");
        }
        if (!scheduleSlotRepository.findOverlapping(instructor.getId(), request.startTime(), request.endTime()).isEmpty()) {
            throw new ConflictException("This time window overlaps an existing slot for this instructor");
        }

        ScheduleSlot slot = ScheduleSlot.builder()
                .instructor(instructor)
                .startTime(request.startTime())
                .endTime(request.endTime())
                .durationMinutes(request.durationMinutes())
                .capacityGroups(request.capacity())
                .bookedCount(0)
                .locationType(request.locationType())
                .meetingUrl(request.meetingUrl())
                .status(SlotStatus.AVAILABLE)
                .build();
        return scheduleSlotRepository.save(slot);
    }

    public ScheduleSlot getById(UUID id) {
        return scheduleSlotRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("ScheduleSlot", id));
    }

    public Page<ScheduleSlot> search(UUID instructorId, SlotStatus status, Instant fromDate, Instant toDate, Pageable pageable) {
        Specification<ScheduleSlot> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (instructorId != null) {
                predicates.add(cb.equal(root.get("instructor").get("id"), instructorId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startTime"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startTime"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return scheduleSlotRepository.findAll(spec, pageable);
    }
}
