package com.capstone.tracking.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScheduleGuard implements ApplicationRunner {
    private final ScheduleMutexRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        // Flyway supplies the row in production; create-drop test databases need it too.
        if (!repository.existsById(1)) repository.saveAndFlush(new ScheduleMutex(1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        if (repository.lockCalendar() == null) throw new IllegalStateException("Schedule lock is missing");
    }
}
