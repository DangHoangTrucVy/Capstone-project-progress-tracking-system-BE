package com.capstone.tracking.user;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.notification.email.EmailMessage;
import com.capstone.tracking.notification.email.EmailOutbox;
import com.capstone.tracking.notification.email.EmailOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Admin review of students who signed up with a personal email: approve once they are confirmed as students of the
 * school (the account becomes ACTIVE), or reject with a reason. The student is emailed either way.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository userRepository;
    private final EmailOutboxRepository emailOutboxRepository;

    @Transactional(readOnly = true)
    public Page<User> list(UserStatus status, Pageable pageable) {
        return userRepository.findBySelfRegisteredTrueAndStatus(status, pageable);
    }

    @Transactional
    public User approve(UUID id) {
        User user = requireRegistration(id);
        if (user.getStatus() != UserStatus.PENDING_APPROVAL && user.getStatus() != UserStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_NOT_PENDING", "This sign-up was already approved");
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setRejectionReason(null);
        email(user, "Tài khoản của bạn đã được duyệt",
                "Chào " + user.getFullName() + ",\n\n"
                        + "Admin đã xác nhận bạn là sinh viên của trường (MSSV " + user.getStudentCode() + "). "
                        + "Bạn có thể đăng nhập hệ thống bằng email " + user.getEmail() + ".");
        return user;
    }

    @Transactional
    public User reject(UUID id, String reason) {
        User user = requireRegistration(id);
        if (user.getStatus() != UserStatus.PENDING_APPROVAL) {
            throw new ApiException(HttpStatus.CONFLICT, "REGISTRATION_NOT_PENDING", "Only a pending sign-up can be rejected");
        }
        user.setStatus(UserStatus.REJECTED);
        user.setRejectionReason(reason.trim());
        email(user, "Đăng ký tài khoản chưa được chấp nhận",
                "Chào " + user.getFullName() + ",\n\n"
                        + "Đăng ký của bạn chưa được chấp nhận. Lý do: " + user.getRejectionReason() + "\n\n"
                        + "Bạn có thể đăng ký lại với thông tin chính xác bằng cùng email.");
        return user;
    }

    private User requireRegistration(UUID id) {
        User user = userRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("User", id));
        if (!user.isSelfRegistered()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_REGISTRATION", "This account was not created by sign-up");
        }
        return user;
    }

    private void email(User user, String subject, String body) {
        emailOutboxRepository.save(EmailOutbox.of(UUID.randomUUID(),
                new EmailMessage(List.of(user.getEmail()), List.of(), subject, body)));
    }
}
