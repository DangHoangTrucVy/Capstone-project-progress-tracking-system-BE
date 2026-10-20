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

    @Transactional
    public User create(UserCreateRequest request) {
        if (request.role() != Role.ADMIN) {
            throw new com.capstone.tracking.common.exception.BadRequestException(
                    "Student and lecturer accounts must come from their imported lists");
        }
        if (request.password() != null) {
            throw new com.capstone.tracking.common.exception.BadRequestException("Only Google sign-in is supported");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }
        return userRepository.save(User.builder()
                .email(email)
                .fullName(request.fullName().trim())
                .role(request.role())
                .phone(request.phone())
                .status(UserStatus.ACTIVE)
                .build());
    }

    public User getById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("User", id));
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
        boolean contactChange = !request.fullName().trim().equals(user.getFullName())
                || (request.email() != null && !request.email().trim().equalsIgnoreCase(user.getEmail()))
                || !java.util.Objects.equals(request.phone(), user.getPhone());
        if (contactChange && enrollments.findByUserId(id).stream()
                .anyMatch(e -> e.getScope().getPhase() == com.capstone.tracking.subject.ScopePhase.FINALIZED)) {
            throw new com.capstone.tracking.common.exception.ConflictException("Reopen finalized lists before editing student contact details");
        }
        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            String email = request.email().trim().toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmailIgnoreCase(email)) {
                throw new ConflictException("A user with email " + email + " already exists");
            }
            user.setEmail(email);
            if (user.getRole() == Role.LECTURER) {
                user.setLecturerImported(false);
            }
        }
        user.setFullName(request.fullName().trim());
        user.setPhone(request.phone());
        user.setStatus(request.status());
        return user;
    }
}
