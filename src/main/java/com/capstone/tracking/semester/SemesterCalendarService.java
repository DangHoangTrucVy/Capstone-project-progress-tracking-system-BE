package com.capstone.tracking.semester;

import com.capstone.tracking.common.VnTime;
import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ConflictException;
import com.capstone.tracking.review.ReviewRound;
import com.capstone.tracking.review.ReviewSessionRepository;
import com.capstone.tracking.scheduling.ScheduleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class SemesterCalendarService {
    private final SemesterCalendarRepository repository;
    private final ReviewSessionRepository reviews;
    private final ScheduleGuard guard;

    @Transactional
    public SemesterCalendar save(String semester, LocalDate startDate) {
        if (semester.isBlank() || semester.length() > 20) {
            throw new BadRequestException("Semester must contain between 1 and 20 characters");
        }
        guard.acquire();
        if (reviews.existsByGroup_Semester(semester)) {
            SemesterCalendar current = repository.findById(semester).orElse(null);
            if (current != null && !current.getStartDate().equals(startDate)) {
                throw new ConflictException("Cannot change semester dates after reviews have been scheduled");
            }
            if (current == null) {
                for (var review : reviews.findByGroup_Semester(semester)) {
                    requireReviewWeek(startDate, review.getRound(), review.getScheduledAt());
                }
            }
        }
        return repository.save(new SemesterCalendar(semester, startDate));
    }

    public SemesterCalendar get(String semester) {
        return repository.findById(semester).orElseThrow(() ->
                new BadRequestException("Configure the start date of semester " + semester + " before scheduling reviews"));
    }

    public void requireReviewWeek(String semester, ReviewRound round, Instant at) {
        requireReviewWeek(get(semester).getStartDate(), round, at);
    }

    private void requireReviewWeek(LocalDate startDate, ReviewRound round, Instant at) {
        long day = ChronoUnit.DAYS.between(startDate, at.atZone(VnTime.ZONE).toLocalDate());
        long week = Math.floorDiv(day, 7) + 1;
        boolean allowed = switch (round) {
            case REVIEW_1 -> week == 3 || week == 4;
            case REVIEW_2 -> week == 7;
            case REVIEW_3 -> week == 14;
        };
        if (!allowed) throw new BadRequestException(round + " cannot be scheduled in semester week " + week);
    }
}
