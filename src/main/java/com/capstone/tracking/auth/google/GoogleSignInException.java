package com.capstone.tracking.auth.google;

import org.springframework.security.authentication.BadCredentialsException;

/** A Google ID token that was rejected; unlike a wrong password, its message is safe and useful to show the user. */
public class GoogleSignInException extends BadCredentialsException {

    public GoogleSignInException(String message) {
        super(message);
    }
}
