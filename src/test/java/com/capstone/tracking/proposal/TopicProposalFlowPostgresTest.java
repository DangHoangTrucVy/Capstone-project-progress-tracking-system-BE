package com.capstone.tracking.proposal;

import com.capstone.tracking.support.PostgresTest;

/** Same flows as {@link TopicProposalFlowIntegrationTest}, on a real PostgreSQL 16 built by the Flyway migrations. */
@PostgresTest
class TopicProposalFlowPostgresTest extends TopicProposalFlowIntegrationTest {
}
