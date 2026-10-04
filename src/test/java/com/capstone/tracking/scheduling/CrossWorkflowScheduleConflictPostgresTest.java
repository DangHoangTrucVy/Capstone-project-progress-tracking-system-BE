package com.capstone.tracking.scheduling;

import com.capstone.tracking.support.PostgresTest;

/** Same flows as {@link CrossWorkflowScheduleConflictIntegrationTest}, on a real PostgreSQL 16 built by the Flyway migrations. */
@PostgresTest
class CrossWorkflowScheduleConflictPostgresTest extends CrossWorkflowScheduleConflictIntegrationTest {
}
