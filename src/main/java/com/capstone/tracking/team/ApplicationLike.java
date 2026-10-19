package com.capstone.tracking.team;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A member's Like on an applicant (8.2): advice only, the Leader decides without waiting for Likes. */
@Entity
@Table(name = "application_likes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationLike extends BaseEntity {

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private UUID userId;
}
