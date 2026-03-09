/**
 * Runtime-level integration tests for the SQS dispatch pipeline.
 *
 * Tests the full queue contract via `pollOnce()`:
 * - Enqueue message to real LocalStack SQS
 * - `pollOnce()` receives, routes, processes, and deletes
 * - Assert structured results and DB/queue state
 *
 * Uses real SQS, real Postgres, real encryption, mocked Google API.
 *
 * @see agent/src/task-dispatcher.ts
 */

// Mock ONLY googleapis
jest.mock('googleapis', () => ({
  google: {
    auth: {
      OAuth2: jest.fn().mockImplementation(() => ({
        setCredentials: jest.fn(),
        refreshAccessToken: jest.fn(),
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
import { pollOnce } from '../task-dispatcher';
import { encryptLikeBackend } from './setup/test-encryption';
import {
    deleteTestData,
    getAvailabilityWindows,
    insertCalendarIntegration,
    insertOrganization,
    insertTeam,
    insertUser,
} from './setup/test-helpers';
import {
    closeTestSqsClient,
    getQueueMessageCount,
    getQueueUrl,
    getSqsClient,
    purgeQueue,
    sendTask,
} from './setup/test-sqs';

// Fixture IDs
let orgId: string;
let teamId: string;
let userId: string;

/**
 * Stable plaintext tokens used to seed encrypted integration rows for SQS tests.
 */
const TEST_ACCESS_TOKEN = 'ya29.sqs-dispatch-test-token';
const TEST_REFRESH_TOKEN = '1//0d-sqs-dispatch-refresh';

beforeAll(async () => {
  orgId = await insertOrganization({ name: 'SQS Dispatch Integration Org' });
  teamId = await insertTeam(orgId, { name: 'SQS Dispatch Integration Team' });
  userId = await insertUser({ displayName: 'SQS Dispatch Integration User' });
});

afterAll(async () => {
  await deleteTestData({ userIds: [userId], teamIds: [teamId], orgIds: [orgId] });
  closeTestSqsClient();
  await closeDb();
});

beforeEach(async () => {
  jest.clearAllMocks();
  await purgeQueue();
  await query('DELETE FROM availability_windows WHERE user_id = $1', [userId]);
  await query('DELETE FROM calendar_integrations WHERE user_id = $1', [userId]);
});

describe('SQS dispatch integration via pollOnce()', () => {
  it('receives SYNC_CALENDAR, dispatches to worker, deletes message, and writes to DB', async () => {
    // Arrange: calendar integration in DB
    await insertCalendarIntegration(
      userId,
      encryptLikeBackend(TEST_ACCESS_TOKEN),
      encryptLikeBackend(TEST_REFRESH_TOKEN),
      new Date(Date.now() + 3600 * 1000),
    );

    // Mock Google FreeBusy
    const mockCalendar = google.calendar({ version: 'v3' });
    (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
      data: {
        calendars: {
          primary: {
            busy: [{ start: '2026-04-10T10:00:00Z', end: '2026-04-10T11:00:00Z' }],
          },
        },
      },
    });

    // Enqueue to real SQS
    await sendTask({ taskType: 'SYNC_CALENDAR', userId, teamId });

    // Act: one poll batch (short wait since message is already in queue)
    const result = await pollOnce(getSqsClient(), getQueueUrl(), 5, 10);

    // Assert: structured result
    expect(result).toEqual({
      received: 1,
      processed: 1,
      deleted: 1,
      failed: 0,
    });

    // Assert: DB state
    const windows = await getAvailabilityWindows(userId);
    expect(windows).toHaveLength(1);
    expect(windows[0].source).toBe('google_cal');
  });

  it('deletes message for unknown task type (non-fatal)', async () => {
    // Enqueue an unknown task type
    await sendTask({ taskType: 'UNKNOWN_TASK_TYPE', payload: 'test' });

    const consoleSpy = jest.spyOn(console, 'warn').mockImplementation();

    const result = await pollOnce(getSqsClient(), getQueueUrl(), 5, 10);

    // Unknown type is non-fatal: processed + deleted, not failed
    expect(result).toEqual({
      received: 1,
      processed: 1,
      deleted: 1,
      failed: 0,
    });

    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('Unknown task type: UNKNOWN_TASK_TYPE'),
    );

    consoleSpy.mockRestore();
  });

  it('leaves message in queue when worker throws (retry behavior)', async () => {
    // Arrange: NO calendar integration → worker will throw
    // (userId exists but has no calendar_integrations row)
    await sendTask({ taskType: 'SYNC_CALENDAR', userId, teamId });

    const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation();

    const result = await pollOnce(getSqsClient(), getQueueUrl(), 5, 10);

    // Assert: message failed, not deleted
    expect(result).toEqual({
      received: 1,
      processed: 0,
      deleted: 0,
      failed: 1,
    });

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining('Task processing failed'),
      expect.stringContaining('No calendar integration found'),
    );

    consoleErrorSpy.mockRestore();

    // Verify message is still in the queue (visible + not-visible).
    // After a failed processMessage, the message is not deleted — it becomes
    // invisible until the SQS visibility timeout expires. getQueueMessageCount
    // includes both visible and not-visible messages.
    const queueDepth = await getQueueMessageCount();
    expect(queueDepth).toBeGreaterThanOrEqual(1);
  });
});
