import { close } from '../../db';
import { closeTestSqsClient } from './test-sqs';

/**
 * Jest global teardown for integration tests.
 *
 * Closes the shared database connection pool from `db.ts` to prevent
 * dangling connections after the test suite completes, and destroys the
 * shared SQS client so Node does not retain open HTTP handles.
 */
export default async function globalTeardown(): Promise<void> {
  closeTestSqsClient();
  await close();
}
