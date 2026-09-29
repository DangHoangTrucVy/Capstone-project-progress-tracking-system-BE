package com.capstone.tracking.scheduling;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.config.CacheConfig;
import com.capstone.tracking.group.StudentGroup;
import com.capstone.tracking.group.StudentGroupService;
import com.capstone.tracking.notification.DomainEvent;
import com.capstone.tracking.notification.DomainEventType;
import com.capstone.tracking.meeting.MeetingSession;
import com.capstone.tracking.meeting.MeetingSessionRepository;
import com.capstone.tracking.meeting.SessionStatus;
import com.capstone.tracking.scheduling.dto.BookRequest;
import com.capstone.tracking.scheduling.dto.CancelBookingRequest;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * Sprint 2 — API-003 / API-004, the highest-risk piece of the whole system per NFR-002 and R-001
 * ("≥50 nhóm đồng thời đặt vào một slot cuối cùng"). {@link #book} is the one place that must not
 * let two requests both believe they got the last seat; see {@link ScheduleSlotRepository#findByIdForUpdate}
 * for the actual locking mechanism this method relies on.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Duration LATE_CANCELLATION_WINDOW = Duration.ofHours(2);
    /** Bước 3.2: a slot must be booked at least 24 hours ahead. */
    static final Duration MIN_BOOKING_NOTICE = Duration.ofHours(24);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BookingRepository bookingRepository;
    private final ScheduleSlotRepository scheduleSlotRepository;
    private final StudentGroupService studentGroupService;
    private final MeetingSessionRepository meetingSessionRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.SLOT_SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.SLOT, key = "#slotId")})
    public Booking book(UUID slotId, BookRequest request, User actingUser) {
        StudentGroup group = studentGroupService.lockById(request.groupId());
        // Only the group's own leader books for it (Giai đoạn 1 note: booking is a Leader-only function).
        studentGroupService.requireActiveLeader(group.getId(), actingUser);

        // Bước 3.2 "phải hoàn thành buổi meeting cũ trước khi đặt lịch mới": a booking stays CONFIRMED until its
        // meeting session is ended (-> ATTENDED) or it is cancelled, so one CONFIRMED booking blocks the next.
        if (bookingRepository.existsByGroupIdAndBookingStatus(group.getId(), BookingStatus.CONFIRMED)) {
            throw new BadRequestException("Group " + group.getGroupCode()
                    + " already has an active booking; finish or cancel that meeting before booking another");
        }

        // Row lock acquired here and held for the rest of this transaction: any other request racing for
        // the same slot blocks at this line until we commit or roll back, instead of both proceeding on
        // stale capacity numbers (5b in UC-002 / R-001's mitigation).
        ScheduleSlot slot = scheduleSlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> ResourceNotFoundException.of("ScheduleSlot", slotId));

        if (!slot.hasCapacity()) {
            throw new ConflictException("Slot " + slotId + " is already full");
        }
        if (slot.getStatus() == SlotStatus.CANCELLED || slot.getStatus() == SlotStatus.COMPLETED) {
            throw new ConflictException("Slot " + slotId + " is " + slot.getStatus().name().toLowerCase());
        }
        if (slot.getStartTime().isBefore(clock.instant().plus(MIN_BOOKING_NOTICE))) {
            throw new BadRequestException("Slots must be booked at least 24 hours before they start");
        }
        LocalDate day = slot.getStartTime().atZone(VN).toLocalDate();
        if (bookingRepository.existsForGroupBetween(group.getId(), EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.ATTENDED),
                day.atStartOfDay(VN).toInstant(), day.plusDays(1).atStartOfDay(VN).toInstant())) {
            throw new ConflictException("Group " + group.getGroupCode() + " already has a meeting on " + day
                    + "; at most one slot per day");
        }

        slot.setBookedCount(slot.getBookedCount() + 1);
        if (!slot.hasCapacity()) {
            slot.setStatus(SlotStatus.FULL);
        }

        Booking booking = Booking.builder()
                .slot(slot)
                .group(group)
                .bookingStatus(BookingStatus.CONFIRMED)
                .bookedAt(clock.instant())
                .notes(request.notes())
                .build();
        booking = bookingRepository.save(booking);

        auditService.record("Booking", booking.getId(), AuditAction.CREATE, actingUser,
                Map.of("slotId", slotId, "groupId", group.getId()));
        events.publishEvent(DomainEvent.of(DomainEventType.BOOKING_CONFIRMED, group.getId(), booking.getId(),
                actingUser.getId(), slotLabel(slot)).withInstructor(slot.getInstructor().getId()));

        return booking;
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.SLOT_SEARCH, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.SLOT, allEntries = true)})
    public void cancel(UUID bookingId, CancelBookingRequest request, User actingUser) {
        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));

        if (actingUser.getRole() == Role.GROUP_LEADER) {
            studentGroupService.requireActiveLeader(booking.getGroup().getId(), actingUser);
        } else if (actingUser.getRole() != Role.ADMIN
                && !booking.getSlot().getInstructor().getId().equals(actingUser.getId())) {
            throw new AccessDeniedException("Only the group's leader or slot instructor can cancel");
        }

        if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only a Confirmed booking can be cancelled");
        }

        // Re-lock the slot before touching its counters, for the same reason book() does.
        ScheduleSlot slot = scheduleSlotRepository.findByIdForUpdate(booking.getSlot().getId())
                .orElseThrow(() -> ResourceNotFoundException.of("ScheduleSlot", booking.getSlot().getId()));

        // If a meeting session exists for this booking, check its status and handle it
        Optional<MeetingSession> sessionOpt = meetingSessionRepository.findByBookingId(booking.getId());
        if (sessionOpt.isPresent()) {
            MeetingSession session = sessionOpt.get();
            if (session.getSessionStatus() == SessionStatus.IN_PROGRESS
                    || session.getSessionStatus() == SessionStatus.CONCLUDED) {
                throw new BadRequestException("Cannot cancel booking for a meeting that is already "
                        + session.getSessionStatus().name().toLowerCase());
            }
        }

        Instant now = clock.instant();
        if (now.isAfter(slot.getStartTime().minus(LATE_CANCELLATION_WINDOW))) {
            throw new BadRequestException("Cannot cancel within 2 hours of the slot's start time (Late Cancellation)");
        }

        if (sessionOpt.isPresent()) {
            MeetingSession session = sessionOpt.get();
            session.setSessionStatus(SessionStatus.CANCELLED);
            meetingSessionRepository.save(session);
            auditService.record("MeetingSession", session.getId(), AuditAction.CANCEL, actingUser,
                    Map.of("reason", request != null && request.reason() != null ? request.reason() : "Booking cancelled"));
        }

        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);

        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        if (slot.getStatus() == SlotStatus.FULL && slot.hasCapacity()) {
            slot.setStatus(SlotStatus.AVAILABLE);
        }

        auditService.record("Booking", booking.getId(), AuditAction.CANCEL, actingUser,
                Map.of("reason", request != null && request.reason() != null ? request.reason() : ""));
        events.publishEvent(DomainEvent.of(DomainEventType.BOOKING_CANCELLED, booking.getGroup().getId(), booking.getId(),
                actingUser.getId(), slotLabel(slot)).withInstructor(slot.getInstructor().getId()));
    }

    private static final DateTimeFormatter SLOT_LABEL =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Notification text is read by Vietnamese users, so the slot time is shown in Vietnam time. */
    private static String slotLabel(ScheduleSlot slot) {
        return "lúc " + SLOT_LABEL.format(slot.getStartTime());
    }
}
