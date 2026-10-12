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

    /**
     * Active student accounts with no active group membership (in {@code semester}, or in any semester when
     * {@code anySemester}) — the "Chua_co_nhom" sheet of the handover export.
     */
    @Query("""
            select u from User u
            where u.role in :roles and u.status = :status
              and not exists (select m from GroupMember m where m.user = u and m.status = :active
                              and (:anySemester = true or m.group.semester = :semester))
            order by u.email""")
    java.util.List<User> findStudentsWithoutGroup(@Param("roles") java.util.Collection<Role> roles,
                                                  @Param("status") UserStatus status,
                                                  @Param("active") com.capstone.tracking.group.MemberStatus active,
                                                  @Param("anySemester") boolean anySemester,
                                                  @Param("semester") String semester);

    /** Students brought in by the list import, optionally only (in)eligible ones and matching a keyword. */
    @Query("""
            select u from User u
            where u.importedAt is not null
              and (:anyEligibility = true or u.eligible = :eligible)
              and (lower(u.email) like :q or lower(u.fullName) like :q or lower(coalesce(u.studentCode, '')) like :q)""")
    Page<User> searchImported(@Param("anyEligibility") boolean anyEligibility, @Param("eligible") boolean eligible,
                              @Param("q") String q, Pageable pageable);
}
