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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User create(UserCreateRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with email " + email + " already exists");
        }
        return userRepository.save(User.builder()
                .email(email)
                .fullName(request.fullName().trim())
                .passwordHash(request.password() == null ? null : passwordEncoder.encode(request.password()))
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
