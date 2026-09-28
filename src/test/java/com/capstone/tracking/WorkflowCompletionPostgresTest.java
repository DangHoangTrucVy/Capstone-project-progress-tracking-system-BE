package com.capstone.tracking;

import com.capstone.tracking.support.PostgresTest;

/** Same contract and concurrency scenarios against PostgreSQL with the real migrations. */
@PostgresTest
class WorkflowCompletionPostgresTest extends WorkflowCompletionIntegrationTest {}
