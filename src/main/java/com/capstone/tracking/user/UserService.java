package com.capstone.tracking.user;

import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.user.dto.UserCreateRequest;
import com.capstone.tracking.user.dto.UserUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User create(UserCreateRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A user with email " + request.email() + " already exists");
        }
        User user = User.builder()
                .email(request.email().toLowerCase())
                .fullName(request.fullName())
                .passwordHash(request.password() == null ? null : passwordEncoder.encode(request.password()))
                .role(request.role())
                .campus(request.campus())
                .status(UserStatus.ACTIVE)
                .build();
        return userRepository.save(user);
    }

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    public User getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> ResourceNotFoundException.of("User", email));
    }

    public Page<User> list(Role role, Pageable pageable) {
        return role == null ? userRepository.findAll(pageable) : userRepository.findByRole(role, pageable);
    }

    @Transactional
    public User update(UUID id, UserUpdateRequest request) {
        User user = getById(id);
        user.setFullName(request.fullName());
        user.setAvatarUrl(request.avatarUrl());
        user.setStatus(request.status());
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.campus() != null) {
            user.setCampus(request.campus());
        }
        return user; // managed entity: change tracked automatically within the transaction
    }
}
