import { close } from '../../db';

/**
 * Jest global teardown for integration tests.
 *
 * Closes the shared database connection pool from `db.ts` to prevent
 * dangling connections after the test suite completes.
 */
export default async function globalTeardown(): Promise<void> {
  await close();
}
