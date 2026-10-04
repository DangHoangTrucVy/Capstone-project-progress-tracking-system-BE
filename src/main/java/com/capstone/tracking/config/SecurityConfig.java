package com.capstone.tracking.config;

import com.capstone.tracking.security.AuthEntryPoint;
import com.capstone.tracking.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT-based security matching blueprint.md §11:
 * - no HTTP session (every request carries its own Bearer token)
 * - RBAC enforced with @PreAuthorize at the controller layer (see UserController, method-security enabled below)
 * - /api/v1/auth/register, /api/v1/auth/login, and Swagger UI are the only endpoints reachable without
 *   a token (/api/v1/auth/me requires one, since it reads the caller's own identity)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthEntryPoint authEntryPoint;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/google",
            "/api/v1/auth/google/config",
            "/api/v1/auth/campuses",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    private static final String[] ALL_ROLES = {"ADMIN", "INSTRUCTOR", "COUNCIL", "GROUP_LEADER", "STUDENT"};

    private static final String[] STUDENT_ENDPOINTS = {
            "/api/v1/auth/me",
            "/api/v1/eligibility/me",
            "/api/v1/me/**",
            "/api/v1/notifications/**",
            "/api/v1/groups",
            "/api/v1/groups/*",
            "/api/v1/groups/*/applications",
            "/api/v1/groups/*/invites",
            "/api/v1/groups/*/leave-requests",
            "/api/v1/applications/**",
            "/api/v1/invites/**",
            "/api/v1/leave-requests/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JWT API, no cookies/CSRF surface
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Students sign in and take part in group formation even without a group (YC02, YC04);
                        // each of these endpoints enforces its own rules with @PreAuthorize / service checks.
                        .requestMatchers(STUDENT_ENDPOINTS).hasAnyRole(ALL_ROLES)
                        .anyRequest().hasAnyRole("ADMIN", "INSTRUCTOR", "COUNCIL", "GROUP_LEADER")
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
