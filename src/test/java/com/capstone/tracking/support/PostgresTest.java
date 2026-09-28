package com.capstone.tracking.support;

import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs a @SpringBootTest against the embedded PostgreSQL 16 instead of H2. See {@link EmbeddedPostgresInitializer}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ContextConfiguration(initializers = EmbeddedPostgresInitializer.class)
public @interface PostgresTest {
}
