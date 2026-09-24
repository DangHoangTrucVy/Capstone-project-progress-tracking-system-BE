package com.capstone.tracking.scheduling;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, UUID>, JpaSpecificationExecutor<ScheduleSlot> {

    /**
     * NFR-002 / R-001: takes a row-level write lock (SELECT ... FOR UPDATE) on the slot for the
     * duration of the caller's transaction, so two concurrent bookings against the last open seat
     * serialize instead of both reading "capacity available" and both writing a Confirmed booking.
     * Only ever call this from inside a method already annotated {@code @Transactional}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduleSlot s where s.id = :id")
    Optional<ScheduleSlot> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Process Analysis §5, step 1 rule: "Không cho phép tạo slot trùng giờ với slot đã có của cùng
     * giảng viên." Two intervals overlap unless one ends at/before the other starts.
     */
    @Query("""
            select s from ScheduleSlot s
            where s.instructor.id = :instructorId
              and s.status <> com.capstone.tracking.scheduling.SlotStatus.CANCELLED
              and s.startTime < :endTime
              and s.endTime > :startTime
            """)
    List<ScheduleSlot> findOverlapping(@Param("instructorId") UUID instructorId,
                                        @Param("startTime") Instant startTime,
                                        @Param("endTime") Instant endTime);
}
