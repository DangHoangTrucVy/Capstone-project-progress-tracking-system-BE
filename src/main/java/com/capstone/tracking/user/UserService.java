package com.capstone.tracking.user;

import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.user.dto.UserCreateRequest;
import com.capstone.tracking.user.dto.UserUpdateRequest;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final com.capstone.tracking.roster.EnrollmentRepository enrollments;
    private final com.capstone.tracking.common.Locks locks;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Transactional
    public User create(UserCreateRequest request) {
        if (request.role() != Role.LECTURER) {
            throw new com.capstone.tracking.common.exception.BadRequestException(
                    "Only Moderator accounts can be created; Admin is provisioned in the database and students are imported");
        }
        if (request.password() == null || request.password().isBlank() || request.password().length() < 8
                || request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new com.capstone.tracking.common.exception.BadRequestException("Moderator password must have at least 8 characters and at most 72 UTF-8 bytes");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }
        return userRepository.save(User.builder()
                .email(email)
                .fullName(request.fullName().trim())
                .role(request.role())
                .passwordHash(encoder.encode(request.password()))
                .phone(request.phone())
                .status(UserStatus.ACTIVE)
                .build());
    }

    public User getById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    /** Allows an Admin to set a password for a former imported lecturer when migrating to Moderator accounts. */
    @Transactional
    public void setModeratorPassword(UUID id, String password) {
        User user = getById(id);
        if (user.getRole() != Role.LECTURER) {
            throw new com.capstone.tracking.common.exception.BadRequestException("Only Moderator passwords are managed here");
        }
        if (password == null || password.length() < 8
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new com.capstone.tracking.common.exception.BadRequestException("Password must have at least 8 characters and at most 72 UTF-8 bytes");
        }
        user.setPasswordHash(encoder.encode(password));
    }

    public Page<User> list(Role role, String keyword, Pageable pageable) {
        String q = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.search(role, q, pageable);
    }

    @Transactional
    public User update(UUID id, UserUpdateRequest request) {
        User user = getById(id);
        enrollments.findByUserId(id).stream().map(e -> e.getScope().getId()).distinct().sorted()
                .forEach(scopeId -> locks.read(com.capstone.tracking.subject.FormationScope.class, scopeId));
        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            String email = request.email().trim().toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmailIgnoreCase(email)) {
                throw new ConflictException("A user with email " + email + " already exists");
            }
            user.setEmail(email);
        }
        user.setFullName(request.fullName().trim());
        user.setPhone(request.phone());
        user.setStatus(request.status());
        return user;
    }
}
