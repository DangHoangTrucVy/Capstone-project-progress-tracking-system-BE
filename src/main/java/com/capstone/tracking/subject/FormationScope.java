package com.capstone.tracking.subject;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.major.Major;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A formation scope ("phạm vi ghép nhóm"): one major within one subject, with its own phase and deadline (3.2, 3.4). */
@Entity
@Table(name = "formation_scopes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormationScope extends BaseEntity {

    /** EAGER: almost every check reads the subject (semester, sizes, TTL), and responses are built outside the transaction. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "major_id", nullable = false)
    private Major major;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScopePhase phase = ScopePhase.PREPARING;

    /** The scope's own deadline; null = the semester's common deadline (3.5). */
    private Instant deadlineOverride;

    private Instant openedAt;
    private Instant lockedAt;
    private Instant finalizedAt;
    private UUID finalizedBy;
    /** When the list was finalized before the last reopening (shown in the Excel header). */
    private Instant previousFinalizedAt;
    private Instant reopenedAt;
    private UUID reopenedBy;

    @Column(length = 1000)
    private String reopenReason;

    /** "Thay đổi gần nhất sau chốt": the last Leader transfer after finalizing (6.4). */
    @Column(length = 500)
    private String lastChangeAfterFinalize;

    private Instant lastChangeAfterFinalizeAt;

    /** When the Moderator was told this scope cannot open (missing Moderator, students or deadline). */
    private Instant openBlockedNotifiedAt;

    public String semesterCode() {
        return subject.getSemesterCode();
    }

    /** "Trước deadline": the scope is OPEN and its deadline has not been reached (the deadline instant counts as past). */
    public boolean isOpenAt(Instant now, Instant deadline) {
        return phase == ScopePhase.OPEN && deadline != null && now.isBefore(deadline);
    }
}
