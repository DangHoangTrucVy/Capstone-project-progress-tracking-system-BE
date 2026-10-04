package com.capstone.tracking.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface UserRepository extends JpaRepository<User, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);

    Optional<User> findByEmailIgnoreCase(String email);

    java.util.List<User> findByEmailStartingWithIgnoreCase(String prefix);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findByRole(Role role, Pageable pageable);

    Page<User> findByEligibleFalse(Pageable pageable);

    boolean existsByStudentCodeIgnoreCase(String studentCode);

    Optional<User> findByStudentCodeIgnoreCase(String studentCode);

    Page<User> findBySelfRegisteredTrueAndStatus(UserStatus status, Pageable pageable);
}
