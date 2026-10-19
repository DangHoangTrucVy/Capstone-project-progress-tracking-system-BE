package com.capstone.tracking.roster;

import com.capstone.tracking.major.Major;
import com.capstone.tracking.subject.FormationScope;
import com.capstone.tracking.subject.FormationScopeRepository;
import com.capstone.tracking.subject.Subject;
import com.capstone.tracking.subject.SubjectRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps scopes and enrollments in line with the semester's subjects and student list: a subject is offered to every
 * student whose current term matches it (3.2), in the scope of that student's major, created on demand.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RosterSync {

    private final SubjectRepository subjects;
    private final FormationScopeRepository scopes;
    private final EnrollmentRepository enrollments;
    private final SemesterStudentRepository semesterStudents;

    public FormationScope scopeFor(Subject subject, Major major) {
        return scopes.findBySubjectIdAndMajorId(subject.getId(), major.getId())
                .orElseGet(() -> scopes.saveAndFlush(FormationScope.builder().subject(subject).major(major).build()));
    }

    /** Every student of the semester in the subject's term gets an enrollment (on subject creation). */
    public void syncSubject(Subject subject) {
        for (SemesterStudent ss : semesterStudents.findBySemesterCodeAndCurrentTerm(subject.getSemesterCode(),
                subject.getTargetTerm())) {
            ensure(subject, ss);
        }
    }

    /**
     * After an import created or changed the student's record: enrolled in each subject of their term, in the scope of
     * their major. Enrollments that no longer match (term or major changed while the scope was still being prepared)
     * are moved or removed; the import refuses such changes once a scope has opened.
     */
    public void syncStudent(SemesterStudent ss) {
        List<Subject> semesterSubjects = subjects.findBySemesterCodeOrderByCodeAsc(ss.getSemesterCode());
        for (Enrollment e : enrollments.findBySemesterStudentId(ss.getId())) {
            Subject subject = semesterSubjects.stream().filter(s -> s.getId().equals(e.getSubjectId())).findFirst().orElse(null);
            if (subject == null || subject.getTargetTerm() != ss.getCurrentTerm()) {
                enrollments.delete(e);
            } else if (!e.getScope().getMajor().getId().equals(ss.getMajor().getId())) {
                e.setScope(scopeFor(subject, ss.getMajor()));
            }
        }
        enrollments.flush();
        for (Subject subject : semesterSubjects) {
            if (subject.getTargetTerm() == ss.getCurrentTerm()) {
                ensure(subject, ss);
            }
        }
    }

    private void ensure(Subject subject, SemesterStudent ss) {
        if (enrollments.findBySubjectIdAndUserId(subject.getId(), ss.getUser().getId()).isPresent()) {
            return;
        }
        enrollments.save(Enrollment.builder()
                .subjectId(subject.getId())
                .scope(scopeFor(subject, ss.getMajor()))
                .semesterStudent(ss)
                .user(ss.getUser())
                .build());
    }
}
