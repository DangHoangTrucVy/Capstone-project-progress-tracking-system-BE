package com.capstone.tracking.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingSessionRepository extends JpaRepository<MeetingSession, UUID> {

    Optional<MeetingSession> findByBookingId(UUID bookingId);

    /** Backs the reports summary (API-011): all sessions for groups in a given semester. */
    List<MeetingSession> findByBooking_Group_Semester(String semester);
}
