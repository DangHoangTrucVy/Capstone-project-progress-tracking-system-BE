package com.capstone.tracking.auth.google;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies ID tokens with Google's tokeninfo endpoint, which checks the signature and expiry for us; we then check
 * the audience is one of our OAuth client ids and the email is verified. One HTTP call per sign-in is fine at this
 * scale and avoids pulling in the Google API client library.
 */
@Slf4j
@Component
public class TokenInfoGoogleTokenVerifier implements GoogleTokenVerifier {

    private static final String TOKEN_INFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token={token}";

    private final RestClient restClient = RestClient.create();
    private final Set<String> clientIds;

    public TokenInfoGoogleTokenVerifier(@Value("${app.google.client-ids:}") String clientIds) {
        this.clientIds = Arrays.stream(clientIds.split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toSet());
    }

    @Override
    @SuppressWarnings("unchecked")
    public GoogleIdentity verify(String idToken) {
        if (clientIds.isEmpty()) {
            throw new BadCredentialsException("Google sign-in is not configured (app.google.client-ids)");
        }
        Map<String, Object> claims;
        try {
            claims = restClient.get().uri(TOKEN_INFO_URL, idToken).retrieve().body(Map.class);
        } catch (RestClientException e) {
            log.debug("Google tokeninfo rejected the token: {}", e.getMessage());
            throw new BadCredentialsException("Invalid Google ID token");
        }
        if (claims == null || !clientIds.contains(String.valueOf(claims.get("aud")))) {
            throw new BadCredentialsException("Google ID token was not issued for this application");
        }
        if (!"true".equals(String.valueOf(claims.get("email_verified")))) {
            throw new BadCredentialsException("Google account email is not verified");
        }
        return new GoogleIdentity(
                String.valueOf(claims.get("email")).toLowerCase(),
                (String) claims.get("name"),
                (String) claims.get("picture"),
                (String) claims.get("hd"));
    }
}
