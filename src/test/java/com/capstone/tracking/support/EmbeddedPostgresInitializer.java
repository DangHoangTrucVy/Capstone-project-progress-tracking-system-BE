package com.capstone.tracking.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Points a test context at a real PostgreSQL 16 (binaries run in-process by zonky's embedded-postgres, no Docker) and
 * lets Flyway build the schema exactly as production does: every migration plus the dev seed, then Hibernate
 * {@code validate}s the entities against it. One server is shared by the whole test JVM.
 */
public class EmbeddedPostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static EmbeddedPostgres postgres;

    static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // JVM is exiting anyway
                }
            }));
        }
        return postgres;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "spring.datasource.url=" + postgres().getJdbcUrl("postgres", "postgres"),
                "spring.datasource.username=postgres",
                "spring.datasource.password=postgres",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
                "spring.flyway.out-of-order=true"
        ).applyTo(context);
    }
}
