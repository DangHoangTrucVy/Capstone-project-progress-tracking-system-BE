package com.capstone.tracking.group;

import com.capstone.tracking.common.BaseEntity;
import com.capstone.tracking.topic.Topic;
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

/** blueprint.md §8 Data Model -> StudentGroup entity. */
@Entity
@Table(name = "student_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentGroup extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String groupCode;

    /** EAGER: StudentGroupResponse always reads topicTitle/supervisorName outside the service's
     * transaction (open-in-view is disabled), so LAZY here throws LazyInitializationException. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "topic_id")
    private Topic topic;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supervisor_id")
    private User supervisor;

    /** e.g. "Fall2026" — matches C-001's 15-week semester framing. */
    @Column(nullable = false)
    private String semester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private GroupStatus status = GroupStatus.FORMED;

    /** YC19: once locked, only an Admin changes the roster. Locked by an Admin or by the group's Leader (GV03). */
    @Column(nullable = false)
    @Builder.Default
    private boolean locked = false;

    /** The lock was put by an Admin: only an Admin may lift it. A Leader's own lock they may lift themselves. */
    @Column(name = "locked_by_admin", nullable = false)
    @Builder.Default
    private boolean lockedByAdmin = false;

    /** YC16: approval of the member list by the group's supervisor. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RosterStatus rosterStatus = RosterStatus.DRAFT;

    @Column(length = 1000)
    private String rosterNote;
}
