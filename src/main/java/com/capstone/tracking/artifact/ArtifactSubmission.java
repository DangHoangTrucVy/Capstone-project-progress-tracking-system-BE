package com.capstone.tracking.artifact;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.milestone.Milestone;
import com.capstone.tracking.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * blueprint.md §8 Data Model -> ArtifactSubmission entity (API-005): a group document, optionally tied to a
 * milestone and/or meeting session. Either an uploaded FILE (bytes live in FileStorage, this row keeps only
 * the storage key) or a LINK. The API exposes it as both "artifacts" and "documents".
 */
@Entity
@Table(name = "artifact_submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtifactSubmission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    /** Nullable: a group may submit an artifact ahead of the meeting it will be discussed in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private MeetingSession session;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "milestone_id")
    private Milestone milestone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ArtifactSourceType sourceType = ArtifactSourceType.LINK;

    /** External URL for LINK documents; null for FILE ones (served from /api/v1/artifacts/{id}/file). */
    private String fileUrl;

    /** FILE only: where FileStorage keeps the bytes, plus what the client uploaded. */
    @Column(length = 500)
    private String storageKey;

    private String originalFilename;

    @Column(length = 100)
    private String contentType;

    private Long sizeBytes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private User submittedBy;

    private String fileType;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ArtifactStatus status = ArtifactStatus.SUBMITTED;
}
