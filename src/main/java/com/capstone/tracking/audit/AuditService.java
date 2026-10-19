package com.capstone.tracking.audit;

import com.capstone.tracking.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single write path for {@link SystemAuditTrail} rows. Called inside the business transaction it documents, so the
 * history row and the change commit or roll back together.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final SystemAuditTrailRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityName, UUID entityId, AuditAction action, User performedBy, String semesterCode,
                       Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (Exception e) {
            // Never let a serialization hiccup block the business transaction the audit row rides along with.
            log.warn("Could not serialize audit details for {}#{}: {}", entityName, entityId, e.getMessage());
            json = "{}";
        }
        repository.save(SystemAuditTrail.builder()
                .entityName(entityName)
                .entityId(entityId)
                .action(action.name())
                .performedBy(performedBy)
                .performedAt(Instant.now())
                .semesterCode(semesterCode)
                .detailsJson(json)
                .build());
    }

    @Transactional(readOnly = true)
    public Page<SystemAuditTrail> list(String semesterCode, Pageable pageable) {
        return repository.findBySemesterCodeOrderByPerformedAtDesc(semesterCode, pageable);
    }
}
