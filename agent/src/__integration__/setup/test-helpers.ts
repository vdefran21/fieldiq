import crypto from 'crypto';
import { query } from '../../db';

/**
 * Factory functions for inserting test data into Postgres.
 *
 * Each function generates a random UUID and returns it, so tests can
 * reference exact rows for assertions and cleanup. Insert only the
 * columns required by the SYNC_CALENDAR worker — no unnecessary
 * relational seeding (e.g., team_members is not needed).
 *
 * Cleanup is by ID, not TRUNCATE, so these are safe alongside dev data.
 *
 * @see V1__initial_schema.sql for column definitions and constraints
 */

/**
 * Optional organization fields that tests may override when seeding data.
 */
interface OrganizationOverrides {
  /** Explicit UUID for deterministic fixture references. */
  id?: string;
  /** Display name stored on the organization row. */
  name?: string;
  /** Slug stored in the organizations table. */
  slug?: string;
}

/**
 * Optional team fields that tests may override when seeding data.
 */
interface TeamOverrides {
  /** Explicit UUID for deterministic fixture references. */
  id?: string;
  /** Display name stored on the team row. */
  name?: string;
}

/**
 * Optional user fields that tests may override when seeding data.
 */
interface UserOverrides {
  /** Explicit UUID for deterministic fixture references. */
  id?: string;
  /** Parent or coach display name stored on the user row. */
  displayName?: string;
}

/**
 * Identifier groups used by cleanup helpers to remove seeded rows safely.
 */
interface DeleteTestDataOptions {
  /** User IDs to delete after dependent rows are removed. */
  userIds?: string[];
  /** Team IDs to delete after child rows are removed. */
  teamIds?: string[];
  /** Organization IDs to delete after teams are removed. */
  orgIds?: string[];
}

/**
 * Inserts an organization row. Required as FK parent for teams.
 *
 * @returns The generated organization UUID.
 */
export async function insertOrganization(
  overrides: OrganizationOverrides = {},
): Promise<string> {
  const id = overrides.id || crypto.randomUUID();
  const slug = overrides.slug || `test-org-${id.substring(0, 8)}`;
  await query(
    'INSERT INTO organizations (id, name, slug, timezone) VALUES ($1, $2, $3, $4)',
    [id, overrides.name || 'Integration Test Org', slug, 'America/New_York'],
  );
  return id;
}

/**
 * Inserts a team row. Required as FK parent for availability_windows.
 *
 * @returns The generated team UUID.
 */
export async function insertTeam(
  orgId: string,
  overrides: TeamOverrides = {},
): Promise<string> {
  const id = overrides.id || crypto.randomUUID();
  await query(
    'INSERT INTO teams (id, org_id, name, sport) VALUES ($1, $2, $3, $4)',
    [id, orgId, overrides.name || 'Integration Test Team', 'soccer'],
  );
  return id;
}

/**
 * Inserts a user row. Required as FK parent for calendar_integrations
 * and availability_windows.
 *
 * @returns The generated user UUID.
 */
export async function insertUser(
  overrides: UserOverrides = {},
): Promise<string> {
  const id = overrides.id || crypto.randomUUID();
  const phone = `+1555${Date.now().toString().slice(-7)}`;
  await query(
    'INSERT INTO users (id, phone, display_name) VALUES ($1, $2, $3)',
    [id, phone, overrides.displayName || 'Integration Test User'],
  );
  return id;
}

/**
 * Inserts a calendar_integrations row with encrypted tokens.
 *
 * Tokens must be pre-encrypted using {@link encryptLikeBackend} from
 * test-encryption.ts to match the backend's AES-256-GCM format.
 *
 * @param userId The user FK.
 * @param encryptedAccessToken AES-256-GCM encrypted access token (Base64).
 * @param encryptedRefreshToken AES-256-GCM encrypted refresh token (Base64).
 * @param expiresAt Token expiry timestamp.
 * @returns The generated calendar_integrations UUID.
 */
export async function insertCalendarIntegration(
  userId: string,
  encryptedAccessToken: string,
  encryptedRefreshToken: string,
  expiresAt: Date = new Date(Date.now() + 3600 * 1000),
): Promise<string> {
  const id = crypto.randomUUID();
  await query(
    `INSERT INTO calendar_integrations (id, user_id, provider, access_token, refresh_token, expires_at)
     VALUES ($1, $2, 'google', $3, $4, $5)`,
    [id, userId, encryptedAccessToken, encryptedRefreshToken, expiresAt.toISOString()],
  );
  return id;
}

/**
 * Inserts a manual availability window (recurring, by day_of_week).
 *
 * Used to verify the worker's DELETE only targets `source='google_cal'`
 * and preserves manual windows.
 */
export async function insertManualAvailabilityWindow(
  teamId: string,
  userId: string,
  dayOfWeek: number = 6,
  startTime: string = '09:00',
  endTime: string = '12:00',
): Promise<string> {
  const id = crypto.randomUUID();
  await query(
    `INSERT INTO availability_windows (id, team_id, user_id, day_of_week, start_time, end_time, window_type, source)
     VALUES ($1, $2, $3, $4, $5, $6, 'available', 'manual')`,
    [id, teamId, userId, dayOfWeek, startTime, endTime],
  );
  return id;
}

// --- Query helpers for assertions ---

/**
 * Returns all availability_windows for a user, sorted by date and start time.
 *
 * Tests use this to verify exactly what availability data the worker persisted.
 */
export async function getAvailabilityWindows(userId: string) {
  const result = await query(
    'SELECT * FROM availability_windows WHERE user_id = $1 ORDER BY specific_date, start_time',
    [userId],
  );
  return result.rows;
}

/**
 * Returns the calendar_integrations row for a user, or null if none.
 *
 * This is primarily used to assert sync side effects such as `last_synced_at`
 * and refreshed expiry timestamps.
 */
export async function getCalendarIntegration(userId: string) {
  const result = await query(
    'SELECT * FROM calendar_integrations WHERE user_id = $1',
    [userId],
  );
  return result.rows[0] || null;
}

// --- Cleanup ---

/**
 * Deletes test data by specific IDs in FK-safe order.
 *
 * Safe alongside dev data — never uses TRUNCATE.
 */
export async function deleteTestData(opts: DeleteTestDataOptions): Promise<void> {
  if (opts.userIds?.length) {
    await query(
      'DELETE FROM availability_windows WHERE user_id = ANY($1::uuid[])',
      [opts.userIds],
    );
    await query(
      'DELETE FROM calendar_integrations WHERE user_id = ANY($1::uuid[])',
      [opts.userIds],
    );
    await query(
      'DELETE FROM users WHERE id = ANY($1::uuid[])',
      [opts.userIds],
    );
  }
  if (opts.teamIds?.length) {
    await query(
      'DELETE FROM teams WHERE id = ANY($1::uuid[])',
      [opts.teamIds],
    );
  }
  if (opts.orgIds?.length) {
    await query(
      'DELETE FROM organizations WHERE id = ANY($1::uuid[])',
      [opts.orgIds],
    );
  }
}
