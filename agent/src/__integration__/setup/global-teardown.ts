import { close } from '../../db';
import { closeTestSqsClient } from './test-sqs';

/**
 * Jest global teardown for integration tests.
 *
 * Closes the shared database connection pool from `db.ts` to prevent
 * dangling connections after the test suite completes.
 */
export default async function globalTeardown(): Promise<void> {
  closeTestSqsClient();
  await close();
}
