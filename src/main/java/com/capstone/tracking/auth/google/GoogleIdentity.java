package com.capstone.tracking.auth.google;

/** The verified claims of a Google ID token that sign-in needs. {@code hostedDomain} is the Workspace domain ("hd"). */
public record GoogleIdentity(String email, String fullName, String pictureUrl, String hostedDomain) {
}
