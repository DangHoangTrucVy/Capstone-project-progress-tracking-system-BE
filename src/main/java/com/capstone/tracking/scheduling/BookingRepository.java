package com.capstone.tracking.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    java.util.Optional<Booking> lockById(@Param("id") UUID id);

    /**
     * UC-002 precondition: "Nhóm chưa đặt slot nào trong cùng đợt kiểm tra hiện hành." v1 simplifies
     * "đợt kiểm tra" to "system-wide": a group may only hold one CONFIRMED booking at a time. Revisit
     * once an explicit assessment-round entity exists, scoping this check to that round instead.
     */
    boolean existsByGroupIdAndBookingStatus(UUID groupId, BookingStatus bookingStatus);

    /** Bước 3.2 "mỗi ngày tối đa 1 slot": the group's bookings whose slot starts in [from, to). */
    @Query("select count(b) > 0 from Booking b where b.group.id = :groupId and b.bookingStatus in :statuses "
            + "and b.slot.startTime >= :from and b.slot.startTime < :to")
    boolean existsForGroupBetween(@Param("groupId") UUID groupId, @Param("statuses") Collection<BookingStatus> statuses,
                                  @Param("from") Instant from, @Param("to") Instant to);

    /** The group's next confirmed meetings, soonest first (Leader overview). */
    @Query("select b from Booking b join fetch b.slot s join fetch s.instructor where b.group.id = :groupId "
            + "and b.bookingStatus = com.capstone.tracking.scheduling.BookingStatus.CONFIRMED order by s.startTime asc")
    List<Booking> findConfirmedByGroup(@Param("groupId") UUID groupId);
}
