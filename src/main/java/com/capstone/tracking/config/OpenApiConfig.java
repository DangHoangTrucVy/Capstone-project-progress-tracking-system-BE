package com.capstone.tracking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Student Schedule and Guidance Management System API")
                        .version("v1")
                        .description("Sprint 1: Foundation & RBAC (User, StudentGroup, Topic, QuestionBank). "
                                + "See blueprint.md in the project docs repo for the full 5-sprint contract."))
                // Relative URL: "Try it out" calls the host/scheme Swagger was loaded from, so it works behind an HTTPS proxy.
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
