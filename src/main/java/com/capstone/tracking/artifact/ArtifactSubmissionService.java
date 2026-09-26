package com.capstone.tracking.artifact;

import com.capstone.tracking.artifact.dto.ArtifactCreateRequest;
import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.meeting.MeetingSessionRepository;
import com.capstone.tracking.milestone.Milestone;
import com.capstone.tracking.milestone.MilestoneService;
import com.capstone.tracking.storage.FileStorage;
import com.capstone.tracking.storage.FileStorage.StoredFile;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** API-005: group members submit documents (uploaded files or links) per milestone; Instructor/Admin accept them. */
@Service
@RequiredArgsConstructor
public class ArtifactSubmissionService {

    private final ArtifactSubmissionRepository artifactSubmissionRepository;
    private final StudentGroupService studentGroupService;
    private final GroupMemberRepository groupMemberRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final MilestoneService milestoneService;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    /** JSON form: a LINK document. */
    @Transactional
    public ArtifactSubmission create(UUID groupId, ArtifactCreateRequest request, User actingUser) {
        return submit(groupId, request.title(), request.sessionId(), request.milestoneId(), actingUser,
                a -> a.sourceType(ArtifactSourceType.LINK).fileUrl(request.fileUrl()).fileType(request.fileType()));
    }

    /** Multipart form: exactly one of {@code file} (stored by the backend) or {@code url} (a link). */
    @Transactional
    public ArtifactSubmission upload(UUID groupId, String title, UUID sessionId, UUID milestoneId,
                                     MultipartFile file, String url, User actingUser) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasUrl = StringUtils.hasText(url);
        if (hasFile == hasUrl) {
            throw new BadRequestException("Provide either a file or a url, not both");
        }
        if (!StringUtils.hasText(title)) {
            throw new BadRequestException("title is required");
        }
        if (hasUrl) {
            return submit(groupId, title, sessionId, milestoneId, actingUser,
                    a -> a.sourceType(ArtifactSourceType.LINK).fileUrl(url).fileType("link"));
        }

        // Membership is checked inside submit(), but check it before writing to disk too.
        studentGroupService.getById(groupId);
        requireMembership(groupId, actingUser);
        StoredFile stored = fileStorage.store(file, "groups/" + groupId);
        deleteStoredFileIfRolledBack(stored.key());
        return submit(groupId, title, sessionId, milestoneId, actingUser,
                a -> a.sourceType(ArtifactSourceType.FILE)
                        .storageKey(stored.key())
                        .originalFilename(stored.originalFilename())
                        .contentType(stored.contentType())
                        .sizeBytes(stored.sizeBytes())
                        .fileType(StringUtils.getFilenameExtension(stored.originalFilename())));
    }

    @Transactional
    public ArtifactSubmission accept(UUID id, User actingUser) {
        ArtifactSubmission artifact = getById(id);
        if (artifact.getStatus() != ArtifactStatus.SUBMITTED) {
            throw new BadRequestException("Only a Submitted artifact can be accepted");
        }
        artifact.setStatus(ArtifactStatus.ACCEPTED);
        auditService.record("ArtifactSubmission", artifact.getId(), AuditAction.APPROVE, actingUser, Map.of());
        return artifact;
    }

    public ArtifactSubmission getById(UUID id) {
        return artifactSubmissionRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ArtifactSubmission", id));
    }

    public Page<ArtifactSubmission> listByGroup(UUID groupId, UUID milestoneId, Pageable pageable) {
        return milestoneId == null
                ? artifactSubmissionRepository.findByGroupId(groupId, pageable)
                : artifactSubmissionRepository.findByGroupIdAndMilestoneId(groupId, milestoneId, pageable);
    }

    /** Uploaded bytes of a FILE document; students may only download their own group's files. */
    @Transactional(readOnly = true)
    public DownloadableFile loadFile(UUID id, User actingUser) {
        ArtifactSubmission artifact = getById(id);
        requireMembership(artifact.getGroup().getId(), actingUser);
        if (artifact.getSourceType() != ArtifactSourceType.FILE) {
            throw new BadRequestException("This document is a link, open its fileUrl instead");
        }
        return new DownloadableFile(fileStorage.load(artifact.getStorageKey()), artifact.getOriginalFilename(),
                artifact.getContentType());
    }

    public record DownloadableFile(Resource resource, String filename, String contentType) {
    }

    private ArtifactSubmission submit(UUID groupId, String title, UUID sessionId, UUID milestoneId, User actingUser,
                                      Consumer<ArtifactSubmission.ArtifactSubmissionBuilder> source) {
        StudentGroup group = studentGroupService.getById(groupId);
        requireMembership(groupId, actingUser);

        MeetingSession session = null;
        if (sessionId != null) {
            session = meetingSessionRepository.findById(sessionId)
                    .orElseThrow(() -> ResourceNotFoundException.of("MeetingSession", sessionId));
        }
        Milestone milestone = null;
        if (milestoneId != null) {
            milestone = milestoneService.getById(milestoneId);
            if (!milestone.getSemester().equals(group.getSemester())) {
                throw new BadRequestException("Milestone " + milestone.getCode() + " belongs to " + milestone.getSemester()
                        + ", not the group's semester " + group.getSemester());
            }
        }

        // Resubmitting the same title bumps the version and supersedes the previous one.
        int version = 1;
        var previous = artifactSubmissionRepository.findTopByGroup_IdAndTitleOrderByVersionDesc(groupId, title);
        if (previous.isPresent()) {
            ArtifactSubmission old = previous.get();
            old.setStatus(ArtifactStatus.SUPERCEDED);
            version = old.getVersion() + 1;
        }

        ArtifactSubmission.ArtifactSubmissionBuilder builder = ArtifactSubmission.builder()
                .group(group)
                .session(session)
                .milestone(milestone)
                .title(title)
                .version(version)
                .submittedAt(Instant.now())
                .submittedBy(actingUser)
                .status(ArtifactStatus.SUBMITTED);
        source.accept(builder);
        ArtifactSubmission artifact = artifactSubmissionRepository.save(builder.build());

        auditService.record("ArtifactSubmission", artifact.getId(), AuditAction.CREATE, actingUser,
                Map.of("groupId", groupId, "version", version, "sourceType", artifact.getSourceType()));
        return artifact;
    }

    /** The file is written before the row; if the transaction then fails, don't leave the file orphaned. */
    private void deleteStoredFileIfRolledBack(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileStorage.delete(key);
                }
            }
        });
    }

    private void requireMembership(UUID groupId, User actingUser) {
        if ((actingUser.getRole() == Role.STUDENT || actingUser.getRole() == Role.GROUP_LEADER)
                && !groupMemberRepository.existsByGroupIdAndUserIdAndStatus(groupId, actingUser.getId(), MemberStatus.ACTIVE)) {
            throw new AccessDeniedException("You are not an active member of this group");
        }
    }
}
