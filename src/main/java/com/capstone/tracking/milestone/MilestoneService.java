package com.capstone.tracking.milestone;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.milestone.dto.MilestoneCreateRequest;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final AuditService auditService;

    @Transactional
    public Milestone create(MilestoneCreateRequest request, User actingUser) {
        if (milestoneRepository.existsBySemesterAndCodeIgnoreCase(request.semester(), request.code())) {
            throw new ConflictException("Milestone " + request.code() + " already exists in " + request.semester());
        }
        Milestone milestone = milestoneRepository.save(Milestone.builder()
                .code(request.code())
                .name(request.name())
                .description(request.description())
                .semester(request.semester())
                .dueDate(request.dueDate())
                .sequenceNo(request.sequenceNo())
                .build());
        auditService.record("Milestone", milestone.getId(), AuditAction.CREATE, actingUser,
                Map.of("semester", request.semester(), "code", request.code()));
        return milestone;
    }

    public Milestone getById(UUID id) {
        return milestoneRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Milestone", id));
    }

    public List<Milestone> list(String semester) {
        return StringUtils.hasText(semester)
                ? milestoneRepository.findBySemesterOrderBySequenceNoAsc(semester)
                : milestoneRepository.findAllByOrderBySemesterAscSequenceNoAsc();
    }
}
