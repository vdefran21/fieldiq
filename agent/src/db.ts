import { Pool, QueryResult } from 'pg';
import { config } from './config';

/**
 * PostgreSQL connection pool for the agent layer.
 *
 * The agent reads/writes the same database as the Kotlin backend.
 * It reads `calendar_integrations` for OAuth tokens and writes
 * `availability_windows` with FreeBusy busy blocks.
 *
 * Uses a connection pool (max 5 connections) to handle concurrent
 * SQS message processing efficiently.
 */
const pool = new Pool({
  connectionString: config.database.connectionString,
  max: 5,
});

/**
 * Executes a parameterized SQL query against the shared database.
 *
 * @param text The SQL query string with $1, $2, ... placeholders.
 * @param params The parameter values to bind.
 * @returns The query result.
 */
export async function query(
  text: string,
  params?: unknown[],
): Promise<QueryResult> {
  return pool.query(text, params);
}

/**
 * Closes the database connection pool.
 * Called during graceful shutdown.
 */
export async function close(): Promise<void> {
  await pool.end();
}

export { pool };
