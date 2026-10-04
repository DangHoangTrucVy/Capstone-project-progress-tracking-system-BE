package com.capstone.tracking.auth.dto;

import com.capstone.tracking.user.Campus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sign-up for students without a school email: a personal email, password and student code (MSSV). The account is
 * always a STUDENT and stays PENDING_APPROVAL until an Admin confirms the person is a student of the school.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(min = 8, max = 100, message = "Password must be at least 8 characters") String password,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}\\d{5,6}$", message = "Student code must look like SE160368")
        String studentCode,
        @NotNull Campus campus
) {
}
