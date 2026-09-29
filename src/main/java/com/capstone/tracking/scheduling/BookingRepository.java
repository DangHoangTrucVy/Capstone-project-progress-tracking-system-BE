package com.capstone.tracking.scheduling;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(@Param("id") UUID id);

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

    /** Cross-workflow conflict check: active bookings for a group overlapping [startTime, endTime). */
    @Query("""
            select count(b) > 0 from Booking b
            where b.group.id = :groupId
              and b.bookingStatus in :statuses
              and b.slot.startTime < :endTime
              and b.slot.endTime > :startTime
            """)
    boolean existsOverlappingForGroup(@Param("groupId") UUID groupId,
                                      @Param("statuses") Collection<BookingStatus> statuses,
                                      @Param("startTime") Instant startTime,
                                      @Param("endTime") Instant endTime);

    /** Cross-workflow conflict check: active bookings for any of the instructors overlapping [startTime, endTime). */
    @Query("""
            select count(b) > 0 from Booking b
            where b.slot.instructor.id in :instructorIds
              and b.bookingStatus in :statuses
              and b.slot.startTime < :endTime
              and b.slot.endTime > :startTime
            """)
    boolean existsOverlappingForInstructors(@Param("instructorIds") Collection<UUID> instructorIds,
                                            @Param("statuses") Collection<BookingStatus> statuses,
                                            @Param("startTime") Instant startTime,
                                            @Param("endTime") Instant endTime);

    default boolean hasActiveOverlappingBookingForGroup(UUID groupId, Instant startTime, Instant endTime) {
        if (groupId == null) return false;
        return existsOverlappingForGroup(groupId,
                EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.ATTENDED),
                startTime, endTime);
    }

    default boolean hasActiveOverlappingBookingForInstructor(UUID instructorId, Instant startTime, Instant endTime) {
        if (instructorId == null) return false;
        return existsOverlappingForInstructors(List.of(instructorId),
                EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.ATTENDED),
                startTime, endTime);
    }

    default boolean hasActiveOverlappingBookingForInstructors(Collection<UUID> instructorIds, Instant startTime, Instant endTime) {
        if (instructorIds == null || instructorIds.isEmpty()) return false;
        return existsOverlappingForInstructors(instructorIds,
                EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.ATTENDED),
                startTime, endTime);
    }
}
