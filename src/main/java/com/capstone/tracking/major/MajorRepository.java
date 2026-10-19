package com.capstone.tracking.major;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, UUID> {

    Optional<Major> findByCodeIgnoreCase(String code);

    Optional<Major> findByNameIgnoreCase(String name);

    boolean existsByCodeIgnoreCase(String code);

    List<Major> findAllByOrderByCodeAsc();
}
