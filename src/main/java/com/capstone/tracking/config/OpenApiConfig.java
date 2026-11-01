package com.capstone.tracking.config;

import com.capstone.tracking.common.exception.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
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
            "/api/v1/auth/login", "/api/v1/auth/google", "/api/v1/auth/google/config");

    /** Swagger UI shows tags in this order, which follows the semester workflow. */
    private static final Map<String, String> TAGS = new LinkedHashMap<>();

    static {
        TAGS.put("Auth", "Sign in and get a JWT. Start here. Students: Google and imported roster. Admin/Moderator: email and password.");
        TAGS.put("Semesters", "Semester list, common timeline and Moderator assignment (3.1, 3.6)");
        TAGS.put("Majors", "Students' majors; groups are formed within one major (3.3)");
        TAGS.put("Imports", "Student list with optional eligibility, and eligibility updates: preview, then confirm (4.1, 4.2)");
        TAGS.put("Subjects & scopes", "Capstone configuration and major scopes: phases, deadlines, "
                + "extension, finalizing, reopening (3.2-3.5, 7.2)");
        TAGS.put("Student list", "List management by semester, subject and major; eligibility (4.3, 5)");
        TAGS.put("Groups", "Creating a group, the Leader's settings, lock, kick, leaving, search (6, 8.2)");
        TAGS.put("Requests", "Apply / Invite, Likes, leave requests and complaints, Leader transfer (6.2, 6.4, 8.2)");
        TAGS.put("Moderation", "The assigned Moderator arranges all groups in every phase, including after finalization, including locked groups (7.1)");
        TAGS.put("Statistics & export", "Statistics, the handover Excel file (7.3, 10)");
        TAGS.put("Profile", "My profile, private mode and my semesters");
        TAGS.put("Notifications", "My in-app notifications and the real-time stream");
        TAGS.put("Users", "Account management (Admin)");
    }

    private static final String DESCRIPTION = """
            Backend API for the Capstone Team Formation Website (requirements v2.9): \
            same-major groups, semester rosters, moderation, finalization and Excel handover.

            **How to call protected endpoints**
            1. Students: `POST /api/v1/auth/google` with a Google ID token matching the imported roster.
               Admin/Moderator: `POST /api/v1/auth/login` with email and password.
            2. Copy `accessToken` from the response.
            3. Click **Authorize** (top right), paste the token (without `Bearer `) and confirm.

            The token is remembered after a page reload. Each operation lists the roles allowed to call it; \
            use the filter box to search tags.
            """;

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    private static final String ERROR_SCHEMA = "ErrorResponse";

    /** The error answers every endpoint may give; the body is always {@link ErrorResponse} (see GlobalExceptionHandler). */
    private static final Map<String, String> ERRORS = new LinkedHashMap<>();

    static {
        ERRORS.put("400", "Invalid request: validation failed (VALIDATION_FAILED, with fieldErrors) or bad input (BAD_REQUEST)");
        ERRORS.put("401", "Missing, invalid or expired token (UNAUTHORIZED)");
        ERRORS.put("403", "Role or semester not allowed, not the owner (e.g. not this group's Leader), student not "
                + "eligible or an earlier semester (FORBIDDEN, NOT_LEADER, NOT_ELIGIBLE, READ_ONLY_SEMESTER)");
        ERRORS.put("404", "Resource not found (NOT_FOUND)");
        ERRORS.put("409", "Business rule conflict, e.g. group full, already in a group, scope not open or past its "
                + "deadline, list finalized, request no longer pending (CONFLICT, GROUP_FULL, SCOPE_NOT_OPEN...)");
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Team Formation API (Website ghép nhóm)")
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

    /** Orders and describes tags, marks the sign-in endpoints as public and lists error answers. */
    @Bean
    public OpenApiCustomizer cleanupCustomizer() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                PUBLIC_PATHS.forEach(path -> {
                    var item = openApi.getPaths().get(path);
                    if (item != null) {
                        item.readOperations().forEach(op -> op.setSecurity(List.of()));
                    }
                });
                addErrorResponses(openApi);
            }

            List<Tag> tags = new ArrayList<>();
            TAGS.forEach((name, description) -> tags.add(new Tag().name(name).description(description)));
            if (openApi.getTags() != null) {
                openApi.getTags().stream().filter(t -> !TAGS.containsKey(t.getName())).forEach(tags::add);
            }
            openApi.setTags(tags);
        };
    }

    /**
     * Documents the error answers next to each operation: 401/403 when it needs a token, 400 when it takes input,
     * 404 when it names a resource in the path, 409 when it changes something. Codes an operation already documents
     * are left alone.
     */
    private static void addErrorResponses(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        ModelConverters.getInstance().readAll(ErrorResponse.class)
                .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            boolean secured = op.getSecurity() == null || !op.getSecurity().isEmpty();
            boolean input = op.getRequestBody() != null || (op.getParameters() != null && !op.getParameters().isEmpty());
            boolean writes = method != PathItem.HttpMethod.GET;
            if (input) {
                addError(op, "400");
            }
            if (secured) {
                addError(op, "401");
                addError(op, "403");
            } else if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/google")) {
                addError(op, "401", "Google sign-in rejected (GOOGLE_SIGN_IN_FAILED)");
                addError(op, "403", "Email is not on an imported list (NOT_ON_LIST) or the account is inactive");
            }
            if (path.contains("{")) {
                addError(op, "404");
            }
            if (writes && secured) {
                addError(op, "409");
            }
        }));
    }

    private static void addError(Operation op, String code) {
        addError(op, code, ERRORS.get(code));
    }

    private static void addError(Operation op, String code, String description) {
        if (op.getResponses() == null) {
            op.setResponses(new ApiResponses());
        }
        op.getResponses().computeIfAbsent(code, c -> new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)))));
    }
}
