package com.capstone.tracking.group;

import com.capstone.tracking.eligibility.StudentMarkedIneligible;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** YC04: a student marked not eligible leaves their group automatically (same transaction as the flag). */
@Component
@RequiredArgsConstructor
public class IneligibleMemberRemover {

    private final StudentGroupService studentGroupService;

    @EventListener
    public void on(StudentMarkedIneligible event) {
        studentGroupService.removeIneligibleStudent(event.userId(), event.reason());
    }
}
