package com.capstone.tracking.user;

import com.capstone.tracking.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 8.1: the student's own profile (skills, strengths, weaknesses, phone) and private mode. */
@RestController
@RequestMapping("/api/v1/me/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "My profile and private mode")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final UserRepository userRepository;
    private final com.capstone.tracking.roster.EnrollmentRepository enrollments;
    private final com.capstone.tracking.roster.SemesterStudentRepository semesterStudents;
    private final com.capstone.tracking.common.Locks locks;

    public record ProfileRequest(@Size(max = 20) String phone,
                                 @Size(max = 1000) String skills,
                                 @Size(max = 1000) String strengths,
                                 @Size(max = 1000) String weaknesses,
                                 boolean profilePrivate) {}

    @Operation(summary = "Get my profile")
    @GetMapping
    public UserResponse get(@AuthenticationPrincipal User currentUser) {
        return UserResponse.from(currentUser);
    }

    @Operation(summary = "Update my profile")
    @PutMapping
    @Transactional
    public UserResponse update(@AuthenticationPrincipal User currentUser, @Valid @RequestBody ProfileRequest request) {
        User user = userRepository.findById(currentUser.getId()).orElseThrow();
        var semesters = semesterStudents.findByUserNewestFirst(user.getId());
        var currentEnrollments = semesters.isEmpty() ? java.util.List.<com.capstone.tracking.roster.Enrollment>of()
                : enrollments.findBySemesterStudentId(semesters.get(0).getId());
        currentEnrollments.stream().map(e -> e.getScope().getId()).distinct().sorted()
                .forEach(id -> locks.read(com.capstone.tracking.subject.FormationScope.class, id));
        if (currentEnrollments.stream().anyMatch(e -> e.getScope().getPhase()
                == com.capstone.tracking.subject.ScopePhase.FINALIZED)) {
            throw new com.capstone.tracking.common.exception.ConflictException("Reopen the finalized list before editing profiles");
        }
        if (!semesters.isEmpty() && enrollments.findBySemesterStudentId(semesters.get(0).getId()).stream()
                .anyMatch(com.capstone.tracking.roster.Enrollment::isIneligible)) {
            throw new com.capstone.tracking.common.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "NOT_ELIGIBLE", "Ineligible students may only view their profile");
        }
        user.setPhone(trim(request.phone()));
        user.setSkills(trim(request.skills()));
        user.setStrengths(trim(request.strengths()));
        user.setWeaknesses(trim(request.weaknesses()));
        user.setProfilePrivate(request.profilePrivate());
        return UserResponse.from(user);
    }

    private static String trim(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
