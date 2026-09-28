package com.capstone.tracking.defense;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Giai đoạn 6: a group's final defense, attempt 1 or 2, with its room, time, committee and grade. */
@Entity
@Table(name = "defense_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DefenseSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private StudentGroup group;

    /** 1 or 2. */
    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false)
    private int durationMinutes;

    @Column(nullable = false, length = 100)
    private String room;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DefenseCommitteeMember> committee = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DefenseStatus status = DefenseStatus.SCHEDULED;

    /** 0-10. */
    private Double score;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "graded_by")
    private User gradedBy;

    private Instant gradedAt;

    public Instant endsAt() {
        return scheduledAt.plusSeconds(durationMinutes * 60L);
    }

    public Optional<DefenseCommitteeMember> chair() {
        return committee.stream().filter(DefenseCommitteeMember::isChair).findFirst();
    }

    public boolean hasCommitteeMember(User user) {
        return committee.stream().anyMatch(m -> m.getMember().getId().equals(user.getId()));
    }
}
