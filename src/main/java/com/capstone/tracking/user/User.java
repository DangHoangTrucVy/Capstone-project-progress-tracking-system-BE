package com.capstone.tracking.user;

import com.capstone.tracking.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * blueprint.md §8 Data Model -> User entity.
 * Implements Spring Security's UserDetails directly so it can be returned by
 * CustomUserDetailsService without a separate adapter class.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity implements UserDetails {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String fullName;

    /** BCrypt hash. Null when the account only authenticates via Google SSO (A-005). */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    private String avatarUrl;

    /**
     * Whether the student may do the capstone this term (YC03). Logging in and being eligible are separate checks:
     * an ineligible student can sign in and see why, but cannot create a group, Apply or Accept an Invite (YC04).
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean eligible = true;

    /** Recruiting profile (YC22): only the group a student applies to sees it, and only while the Apply is open. */
    @Column(length = 1000)
    private String bio;

    @Column(length = 500)
    private String skills;

    @Column(length = 500)
    private String ineligibleReason;

    /** Campus picked at sign-in (Giai đoạn 1). Null until the first Google sign-in, then pinned. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Campus campus;

    /** Student code (MSSV), given when a student signs up with a personal email. */
    @Column(length = 20, unique = true)
    private String studentCode;

    /** Signed up with a personal email (not provisioned by an Admin); such accounts may sign in outside the school domain. */
    @Column(nullable = false)
    @Builder.Default
    private boolean selfRegistered = false;

    /** Why an Admin rejected the sign-up; shown to the student when they try to sign in. */
    @Column(length = 500)
    private String rejectionReason;

    // --- UserDetails contract -------------------------------------------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.SUSPENDED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
}
