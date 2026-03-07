import { Pool } from 'pg';
import { SQSClient, GetQueueAttributesCommand } from '@aws-sdk/client-sqs';

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
 */
export default async function globalSetup(): Promise<void> {
  // 1. Verify Postgres is reachable and Flyway schema exists
  const pool = new Pool({
    connectionString: process.env.DATABASE_URL || 'postgresql://fieldiq:localdev@localhost:5432/fieldiq',
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
  const sqsEndpoint = process.env.AWS_ENDPOINT_URL || 'http://localhost:4566';
  const queueUrl = process.env.AGENT_TASKS_QUEUE_URL || 'http://localhost:4566/000000000000/fieldiq-agent-tasks';

  const sqsClient = new SQSClient({
    region: process.env.AWS_REGION || 'us-east-1',
    endpoint: sqsEndpoint,
  });

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
  }

  console.log('Integration test prerequisites verified: Postgres schema ✓, SQS queue ✓');
}
