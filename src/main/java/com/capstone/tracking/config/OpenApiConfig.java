package com.capstone.tracking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    /** Matches SecurityConfig.PUBLIC_ENDPOINTS: these operations need no token. */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/google", "/api/v1/auth/google/config",
            "/api/v1/auth/campuses");

    /** Legacy aliases of the /artifacts endpoints; they still work but would list every operation twice. */
    private static final List<String> HIDDEN_PATH_PREFIXES = List.of(
            "/api/v1/documents", "/api/v1/groups/{groupId}/documents");

    /** Swagger UI shows tags in this order, which follows the semester workflow. */
    private static final Map<String, String> TAGS = new LinkedHashMap<>();

    static {
        TAGS.put("Auth", "Sign in and get a JWT. Start here.");
        TAGS.put("Users", "User accounts (Admin) and my own recruiting profile");
        TAGS.put("Semesters", "Semester calendar");
        TAGS.put("Eligibility", "Eligible student list import and the not-eligible flag");
        TAGS.put("Student Groups", "Groups, members, leader, roster lock and roster approval");
        TAGS.put("Group Formation", "Apply, invite, member votes, leave requests, roster change reports, formation deadline");
        TAGS.put("Topics", "Topic catalogue managed by Admin");
        TAGS.put("Question Bank", "Per-topic question bank");
        TAGS.put("Topic Proposals", "Leader submits topics, supervisor forwards one, Council decides; proposal rounds");
        TAGS.put("Milestones", "Semester checkpoints that documents are submitted against");
        TAGS.put("Schedule Slots", "Instructor availability slots");
        TAGS.put("Bookings", "Groups book instructor slots");
        TAGS.put("Meetings", "Meeting sessions started from a confirmed booking");
        TAGS.put("Meeting Minutes", "Generated minutes and Leader / Instructor sign-off");
        TAGS.put("Requirement Logs", "Requirements raised during a meeting");
        TAGS.put("Weekly Progress", "Weekly progress reports, tasks and supervisor feedback");
        TAGS.put("Documents", "Group document submissions (file or link) per milestone. "
                + "Every /artifacts path is also served under /documents.");
        TAGS.put("Evaluations", "Instructor scoring of a group on 3 criteria");
        TAGS.put("Warning Flags", "Supervisor warnings on late groups or inactive members");
        TAGS.put("Reviews", "Review 1, Review 2 and the closed council (Review 3)");
        TAGS.put("Defenses", "Final defense scheduling and grading, attempts 1 and 2");
        TAGS.put("Group Overview", "Leader dashboard in one call");
        TAGS.put("Notifications", "My in-app notifications and the real-time stream");
        TAGS.put("Reports", "Progress statistics");
    }

    private static final String DESCRIPTION = """
            Backend API for tracking capstone project progress: group formation, topics, meetings, \
            weekly progress, documents, reviews and defenses.

            **How to call protected endpoints**
            1. `POST /api/v1/auth/login` (section **Auth**) with your email and password.
            2. Copy `accessToken` from the response.
            3. Click **Authorize** (top right), paste the token (without `Bearer `) and confirm.

            The token is remembered after a page reload. Each operation lists the roles allowed to call it; \
            use the filter box to search tags.
            """;

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Capstone Progress Tracking API")
                        .version("v1")
                        .description(DESCRIPTION))
                // Relative URL: "Try it out" calls the host/scheme Swagger was loaded from, so it works behind an HTTPS proxy.
                .servers(List.of(new Server().url("/")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /** Appends the roles from @PreAuthorize to each operation's description. */
    @Bean
    public OperationCustomizer rolesCustomizer() {
        return (operation, handlerMethod) -> {
            PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
            if (rule == null) {
                rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
            }
            if (rule == null) {
                return operation;
            }
            List<String> roles = new ArrayList<>();
            Matcher m = ROLE.matcher(rule.value());
            while (m.find()) {
                roles.add(m.group(1));
            }
            if (!roles.isEmpty()) {
                String line = "**Roles:** " + String.join(", ", roles);
                String description = operation.getDescription();
                operation.setDescription(description == null || description.isBlank() ? line : description + "\n\n" + line);
            }
            return operation;
        };
    }

    /** Orders and describes tags, hides alias paths and marks the sign-in endpoints as public. */
    @Bean
    public OpenApiCustomizer cleanupCustomizer() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                openApi.getPaths().keySet().removeIf(path -> HIDDEN_PATH_PREFIXES.stream().anyMatch(path::startsWith));
                PUBLIC_PATHS.forEach(path -> {
                    var item = openApi.getPaths().get(path);
                    if (item != null) {
                        item.readOperations().forEach(op -> op.setSecurity(List.of()));
                    }
                });
            }

            List<Tag> tags = new ArrayList<>();
            TAGS.forEach((name, description) -> tags.add(new Tag().name(name).description(description)));
            if (openApi.getTags() != null) {
                openApi.getTags().stream().filter(t -> !TAGS.containsKey(t.getName())).forEach(tags::add);
            }
            openApi.setTags(tags);
        };
    }
}
