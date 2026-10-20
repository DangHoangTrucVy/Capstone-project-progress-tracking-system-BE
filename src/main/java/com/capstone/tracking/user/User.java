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

/** An account: Admin, lecturer or student. Implements UserDetails so the security layer uses it directly. */
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

    /** Only the lecturer import establishes membership of the approved lecturer list (requirement 9). */
    @Column(nullable = false)
    @Builder.Default
    private boolean lecturerImported = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    private String avatarUrl;

    /** Student code (MSSV), from the imported student list. */
    @Column(length = 20, unique = true)
    private String studentCode;

    @Column(length = 20)
    private String phone;

    /** Profile (8.1). */
    @Column(length = 1000)
    private String skills;

    @Column(length = 1000)
    private String strengths;

    @Column(length = 1000)
    private String weaknesses;

    /** A private profile shows only name and student code in searches (8.1). */
    @Column(nullable = false)
    @Builder.Default
    private boolean profilePrivate = false;

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
