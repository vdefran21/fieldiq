import { GetQueueAttributesCommand } from '@aws-sdk/client-sqs';
import { Pool } from 'pg';
import { config } from '../../config';
import { createSqsClient, getAgentTasksQueueUrl } from '../../sqs-client';

/**
 * Jest global setup for integration tests.
 *
 * Runs once before all integration test files. Verifies that required
 * infrastructure is reachable and the database schema has been applied.
 * Fails fast with clear error messages if prerequisites are missing.
 *
 * **Prerequisites:**
 * - `docker compose up -d` (Postgres on :5432, LocalStack on :4566)
 * - Backend booted at least once to run Flyway migrations
 *
 * This setup intentionally fails fast before any tests run so developers get a
 * single actionable infrastructure error instead of a cascade of opaque failures.
 */
export default async function globalSetup(): Promise<void> {
  // 1. Verify Postgres is reachable and Flyway schema exists
  const pool = new Pool({
    connectionString: config.database.connectionString,
    connectionTimeoutMillis: 5000,
  });

  try {
    const result = await pool.query(
      "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'calendar_integrations')",
    );
    if (!result.rows[0].exists) {
      throw new Error(
        'Flyway schema not applied. The calendar_integrations table does not exist.\n' +
        'Start the backend once to run migrations:\n' +
        '  cd backend && SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun\n' +
        'Then stop it and re-run integration tests.',
      );
    }
  } catch (error) {
    if (error instanceof Error && error.message.includes('Flyway schema')) {
      throw error;
    }
    throw new Error(
      'Cannot connect to Postgres at localhost:5432.\n' +
      'Start infrastructure first: docker compose up -d\n' +
      `Original error: ${error instanceof Error ? error.message : String(error)}`,
    );
  } finally {
    await pool.end();
  }

  // 2. Verify LocalStack SQS is reachable and queue exists
  const queueUrl = getAgentTasksQueueUrl();

  const sqsClient = createSqsClient();

  try {
    await sqsClient.send(
      new GetQueueAttributesCommand({
        QueueUrl: queueUrl,
        AttributeNames: ['ApproximateNumberOfMessages'],
      }),
    );
  } catch (error) {
    throw new Error(
      `Cannot reach SQS queue at ${queueUrl}.\n` +
      'Start infrastructure first: docker compose up -d\n' +
      `Original error: ${error instanceof Error ? error.message : String(error)}`,
    );
  } finally {
    sqsClient.destroy();
  }

  console.log('Integration test prerequisites verified: Postgres schema ✓, SQS queue ✓');
}
