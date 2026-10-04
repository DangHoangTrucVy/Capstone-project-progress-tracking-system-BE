package com.capstone.tracking.scheduling;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.scheduling.dto.BookRequest;
import com.capstone.tracking.scheduling.dto.BookingResponse;
import com.capstone.tracking.scheduling.dto.CancelBookingRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** API-003 / API-004 — the concurrency-critical booking flow; see BookingService for the locking strategy. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Group booking of assessment slots (Sprint 2)")
@SecurityRequirement(name = "bearerAuth")
public class BookingController {

    private final BookingService bookingService;

    @Operation(summary = "Group books a slot")
    @PostMapping("/api/v1/slots/{id}/book")
    @PreAuthorize("hasRole('GROUP_LEADER')")
    public ResponseEntity<BookingResponse> book(@PathVariable UUID id,
                                                 @Valid @RequestBody BookRequest request,
                                                 @AuthenticationPrincipal User currentUser) {
        Booking booking = bookingService.book(id, request, currentUser);
        return ResponseEntity.ok(BookingResponse.from(booking));
    }

    @Operation(summary = "Cancel a booking")
    @DeleteMapping("/api/v1/bookings/{id}")
    @PreAuthorize("hasAnyRole('GROUP_LEADER','INSTRUCTOR','ADMIN')")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable UUID id,
                                                        @Valid @RequestBody CancelBookingRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        bookingService.cancel(id, request, currentUser);
        return ResponseEntity.ok(Map.of("message", "Cancelled"));
    }
}
