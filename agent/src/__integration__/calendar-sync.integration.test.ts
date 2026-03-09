/**
 * Worker-level integration tests for the SYNC_CALENDAR task.
 *
 * Calls `handleSyncCalendar()` directly with:
 * - **Real Postgres** (via db.ts pool)
 * - **Real AES-256-GCM encryption** (via encryption.ts)
 * - **Mocked Google Calendar API** (external dependency)
 *
 * Validates the worker's SQL queries, encryption round-trip, and data
 * transformation against the real database schema from V1__initial_schema.sql.
 *
 * @see agent/src/workers/calendar-sync.worker.ts
 */

// Mock ONLY googleapis — everything else (db, encryption, config) is real
jest.mock('googleapis', () => ({
  google: {
    auth: {
      OAuth2: jest.fn().mockImplementation(() => ({
        setCredentials: jest.fn(),
        refreshAccessToken: jest.fn().mockResolvedValue({
          credentials: {
            access_token: 'refreshed-access-token',
            expiry_date: Date.now() + 3600 * 1000,
          },
        }),
      })),
    },
    calendar: jest.fn().mockReturnValue({
      freebusy: {
        query: jest.fn(),
      },
    }),
  },
}));

import { google } from 'googleapis';
import { close as closeDb, query } from '../db';
import { handleSyncCalendar, SyncCalendarTask } from '../workers/calendar-sync.worker';
import { encryptLikeBackend } from './setup/test-encryption';
import {
    deleteTestData,
    getAvailabilityWindows,
    getCalendarIntegration,
    insertCalendarIntegration,
    insertManualAvailabilityWindow,
    insertOrganization,
    insertTeam,
    insertUser,
} from './setup/test-helpers';

// Test fixture IDs — populated in beforeAll
let orgId: string;
let teamId: string;
let userId: string;

/**
 * Stable plaintext tokens used to seed encrypted calendar integration rows.
 *
 * The exact values are not important; what matters is that the worker decrypts
 * backend-compatible ciphertext and passes the resulting access token through
 * the Google FreeBusy client path.
 */
const TEST_ACCESS_TOKEN = 'ya29.integration-test-access-token';
const TEST_REFRESH_TOKEN = '1//0d-integration-test-refresh-token';

beforeAll(async () => {
  orgId = await insertOrganization({ name: 'CalSync Integration Org' });
  teamId = await insertTeam(orgId, { name: 'CalSync Integration Team' });
  userId = await insertUser({ displayName: 'CalSync Integration User' });
});

afterAll(async () => {
  await deleteTestData({ userIds: [userId], teamIds: [teamId], orgIds: [orgId] });
  await closeDb();
});

beforeEach(async () => {
  jest.clearAllMocks();
  // Clean up per-test rows
  await query('DELETE FROM availability_windows WHERE user_id = $1', [userId]);
  await query('DELETE FROM calendar_integrations WHERE user_id = $1', [userId]);
});

/**
 * Helper to build the task payload with the test fixture IDs.
 */
function buildTask(): SyncCalendarTask {
  return { taskType: 'SYNC_CALENDAR', userId, teamId };
}

/**
 * Helper to mock the Google FreeBusy API response.
 *
 * Integration tests still mock Google because the contract under test is the
 * worker's database behavior, not Google's upstream availability.
 */
function mockFreeBusy(busyBlocks: Array<{ start: string; end: string }>) {
  const mockCalendar = google.calendar({ version: 'v3' });
  (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
    data: {
      calendars: {
        primary: { busy: busyBlocks },
      },
    },
  });
}

describe('SYNC_CALENDAR worker integration', () => {
  it('throws when no calendar integration exists for the user', async () => {
    await expect(handleSyncCalendar(buildTask())).rejects.toThrow(
      `No calendar integration found for user ${userId}`,
    );
  });

  it('decrypts tokens, calls FreeBusy, and writes availability windows to DB', async () => {
    // Arrange: encrypted tokens in DB (simulating backend OAuth flow)
    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() + 3600 * 1000),
    );

    mockFreeBusy([
      { start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:30:00Z' },
      { start: '2026-04-12T14:00:00Z', end: '2026-04-12T15:00:00Z' },
    ]);

    // Act
    await handleSyncCalendar(buildTask());

    // Assert: windows written with correct values
    const windows = await getAvailabilityWindows(userId);
    expect(windows).toHaveLength(2);

    // First window
    expect(windows[0].source).toBe('google_cal');
    expect(windows[0].window_type).toBe('unavailable');
    expect(windows[0].team_id).toBe(teamId);
    expect(windows[0].specific_date.toISOString().slice(0, 10)).toBe('2026-04-10');

    // Second window
    expect(windows[1].specific_date.toISOString().slice(0, 10)).toBe('2026-04-12');

    // last_synced_at updated
    const integration = await getCalendarIntegration(userId);
    expect(integration.last_synced_at).not.toBeNull();
  });

  it('replaces stale windows on re-sync', async () => {
    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() + 3600 * 1000),
    );

    // First sync: 2 busy blocks
    mockFreeBusy([
      { start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:00:00Z' },
      { start: '2026-04-11T10:00:00Z', end: '2026-04-11T11:00:00Z' },
    ]);
    await handleSyncCalendar(buildTask());
    expect(await getAvailabilityWindows(userId)).toHaveLength(2);

    // Second sync: only 1 busy block (the other is now free)
    mockFreeBusy([
      { start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:00:00Z' },
    ]);
    await handleSyncCalendar(buildTask());

    // Stale windows deleted, only fresh ones remain
    const windows = await getAvailabilityWindows(userId);
    expect(windows).toHaveLength(1);
    expect(windows[0].specific_date.toISOString().slice(0, 10)).toBe('2026-04-10');
  });

  it('handles empty FreeBusy response with zero windows but updates last_synced_at', async () => {
    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() + 3600 * 1000),
    );

    mockFreeBusy([]);

    await handleSyncCalendar(buildTask());

    expect(await getAvailabilityWindows(userId)).toHaveLength(0);

    const integration = await getCalendarIntegration(userId);
    expect(integration.last_synced_at).not.toBeNull();
  });

  it('preserves manual availability windows during sync', async () => {
    // Insert a manual (recurring) availability window
    await insertManualAvailabilityWindow(teamId, userId, 6, '09:00', '12:00');

    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() + 3600 * 1000),
    );

    mockFreeBusy([
      { start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:00:00Z' },
    ]);

    await handleSyncCalendar(buildTask());

    // Both manual AND google_cal windows should exist
    const result = await query(
      'SELECT source FROM availability_windows WHERE user_id = $1 ORDER BY source',
      [userId],
    );
    const sources = result.rows.map((r: { source: string }) => r.source);
    expect(sources).toEqual(['google_cal', 'manual']);
  });

  it('refreshes expired token and updates expires_at in DB', async () => {
    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() - 3600 * 1000), // expired 1 hour ago
    );

    mockFreeBusy([
      { start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:00:00Z' },
    ]);

    await handleSyncCalendar(buildTask());

    // Verify OAuth2 was instantiated for refresh
    expect(google.auth.OAuth2).toHaveBeenCalled();

    // Verify expires_at was updated (worker updates expires_at, NOT encrypted token)
    const integration = await getCalendarIntegration(userId);
    const newExpiry = new Date(integration.expires_at).getTime();
    expect(newExpiry).toBeGreaterThan(Date.now());

    // Sync still completed despite refresh
    expect(await getAvailabilityWindows(userId)).toHaveLength(1);
  });
});
