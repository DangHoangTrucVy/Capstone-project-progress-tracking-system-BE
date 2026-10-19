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

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findByRole(Role role, Pageable pageable);

    Optional<User> findByStudentCodeIgnoreCase(String studentCode);

    /** Admin's account list: optional role, keyword on email, name or student code. */
    @Query("""
            select u from User u
            where (:role is null or u.role = :role)
              and (lower(u.email) like :q or lower(u.fullName) like :q or lower(coalesce(u.studentCode, '')) like :q)""")
    Page<User> search(@Param("role") Role role, @Param("q") String q, Pageable pageable);
}
