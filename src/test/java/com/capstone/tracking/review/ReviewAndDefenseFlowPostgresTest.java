package com.capstone.tracking.review;

import com.capstone.tracking.support.PostgresTest;

/** Same flows as {@link ReviewAndDefenseFlowIntegrationTest}, on a real PostgreSQL 16 built by the Flyway migrations. */
@PostgresTest
class ReviewAndDefenseFlowPostgresTest extends ReviewAndDefenseFlowIntegrationTest {
}
