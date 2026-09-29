package com.capstone.tracking.meeting;

import com.capstone.tracking.audit.AuditAction;
import com.capstone.tracking.audit.AuditService;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.meeting.dto.EndSessionRequest;
import com.capstone.tracking.scheduling.Booking;
import com.capstone.tracking.scheduling.BookingRepository;
import com.capstone.tracking.scheduling.BookingStatus;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Sprint 4 — the MeetingSession half of API-007/008/009's `{id}` (a session, not a booking). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingSessionService {

    private final MeetingSessionRepository meetingSessionRepository;
    private final BookingRepository bookingRepository;
    private final MeetingWriteAccess writeAccess;
    private final MeetingReadAccess readAccess;
    private final AuditService auditService;

    @Transactional
    public MeetingSession create(UUID bookingId, User actingUser) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
        writeAccess.requireParticipant(booking, actingUser);
        if (booking.getBookingStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("A meeting session can only be started from a Confirmed booking");
        }
        if (meetingSessionRepository.findByBookingId(bookingId).isPresent()) {
            throw new ConflictException("A meeting session already exists for this booking");
        }
        MeetingSession session = MeetingSession.builder()
                .booking(booking)
                .sessionStatus(SessionStatus.SCHEDULED)
                .build();
        session = meetingSessionRepository.save(session);

        auditService.record("MeetingSession", session.getId(), AuditAction.CREATE, actingUser,
                Map.of("bookingId", bookingId));
        return session;
    }

    @Transactional
    public MeetingSession start(UUID sessionId, User actingUser) {
        MeetingSession session = getById(sessionId);
        writeAccess.requireParticipant(session.getBooking(), actingUser);
        if (session.getSessionStatus() != SessionStatus.SCHEDULED) {
            throw new BadRequestException("Only a Scheduled session can be started");
        }
        session.setSessionStatus(SessionStatus.IN_PROGRESS);
        session.setStartedAt(Instant.now());
        auditService.record("MeetingSession", session.getId(), AuditAction.UPDATE, actingUser,
                Map.of("sessionStatus", SessionStatus.IN_PROGRESS));
        return session;
    }

    @Transactional
    public MeetingSession end(UUID sessionId, EndSessionRequest request, User actingUser) {
        MeetingSession session = getById(sessionId);
        writeAccess.requireParticipant(session.getBooking(), actingUser);
        if (session.getSessionStatus() != SessionStatus.IN_PROGRESS) {
            throw new BadRequestException("Only an In Progress session can be ended");
        }
        if (request != null && request.rawNotes() != null && !request.rawNotes().isBlank()) {
            session.setRawNotes(request.rawNotes());
        }
        session.setSessionStatus(SessionStatus.CONCLUDED);
        session.setEndedAt(Instant.now());
        // The meeting happened: frees the group to book its next slot (BookingService.book, bước 3.2).
        session.getBooking().setBookingStatus(BookingStatus.ATTENDED);
        auditService.record("MeetingSession", session.getId(), AuditAction.UPDATE, actingUser,
                Map.of("sessionStatus", SessionStatus.CONCLUDED));
        return session;
    }

    public MeetingSession getById(UUID id) {
        return meetingSessionRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("MeetingSession", id));
    }

    public MeetingSession getById(UUID id, User currentUser) {
        MeetingSession session = getById(id);
        readAccess.requireCanRead(session, currentUser);
        return session;
    }

}
