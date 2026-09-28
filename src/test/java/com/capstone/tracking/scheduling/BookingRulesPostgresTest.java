package com.capstone.tracking.scheduling;

import com.capstone.tracking.support.PostgresTest;

/** Same flows as {@link BookingRulesIntegrationTest}, on a real PostgreSQL 16 built by the Flyway migrations. */
@PostgresTest
class BookingRulesPostgresTest extends BookingRulesIntegrationTest {
}
