package com.capstone.tracking.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    java.util.List<User> findByEmailStartingWithIgnoreCase(String prefix);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findByRole(Role role, Pageable pageable);
}
