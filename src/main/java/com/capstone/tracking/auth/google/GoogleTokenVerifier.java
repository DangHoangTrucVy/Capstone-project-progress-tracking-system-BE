package com.capstone.tracking.auth.google;

/** Checks a Google ID token (from Google Identity Services on the FE) and returns who it belongs to. */
public interface GoogleTokenVerifier {

    /** @throws org.springframework.security.authentication.BadCredentialsException when the token is not valid for this app */
    GoogleIdentity verify(String idToken);
}
