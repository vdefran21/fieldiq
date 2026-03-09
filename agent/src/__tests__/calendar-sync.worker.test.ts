import {
  handleSyncCalendar,
  fetchFreeBusy,
  SyncCalendarTask,
} from '../workers/calendar-sync.worker';

/**
 * Unit tests for the calendar-sync worker.
 *
 * These tests isolate worker orchestration from external systems by mocking:
 * - Postgres query execution
 * - AES token decryption
 * - Google OAuth and FreeBusy responses
 *
 * Integration coverage for the same workflow lives under `src/__integration__/`
 * and protects the real database and queue contracts.
 */

// Mock dependencies
jest.mock('../db', () => ({
  query: jest.fn(),
}));

jest.mock('../encryption', () => ({
  decryptToken: jest.fn(),
}));

jest.mock('googleapis', () => ({
  google: {
    auth: {
      OAuth2: jest.fn().mockImplementation(() => ({
        setCredentials: jest.fn(),
        refreshAccessToken: jest.fn().mockResolvedValue({
          credentials: {
            access_token: 'new-access-token',
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

import { query } from '../db';
import { decryptToken } from '../encryption';
import { google } from 'googleapis';

const mockQuery = query as jest.MockedFunction<typeof query>;
const mockDecrypt = decryptToken as jest.MockedFunction<typeof decryptToken>;

describe('calendar-sync.worker', () => {
  /**
   * Representative queue payload reused across the worker unit suite.
   */
  const task: SyncCalendarTask = {
    taskType: 'SYNC_CALENDAR',
    userId: '550e8400-e29b-41d4-a716-446655440000',
    teamId: '660e8400-e29b-41d4-a716-446655440001',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('handleSyncCalendar', () => {
    it('throws when no calendar integration exists', async () => {
      mockQuery.mockResolvedValueOnce({
        rows: [],
        command: 'SELECT',
        rowCount: 0,
        oid: 0,
        fields: [],
      });

      await expect(handleSyncCalendar(task)).rejects.toThrow(
        'No calendar integration found',
      );
    });

    it('loads and decrypts tokens from the database', async () => {
      const futureDate = new Date(Date.now() + 3600 * 1000).toISOString();

      // 1. SELECT calendar integration
      mockQuery.mockResolvedValueOnce({
        rows: [
          {
            id: 'integration-id',
            access_token: 'encrypted-access',
            refresh_token: 'encrypted-refresh',
            expires_at: futureDate,
          },
        ],
        command: 'SELECT',
        rowCount: 1,
        oid: 0,
        fields: [],
      });

      mockDecrypt.mockReturnValueOnce('decrypted-access-token');
      mockDecrypt.mockReturnValueOnce('decrypted-refresh-token');

      // Mock FreeBusy to return no busy blocks
      const mockCalendar = google.calendar({ version: 'v3' });
      (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
        data: { calendars: { primary: { busy: [] } } },
      });

      // 2. DELETE stale windows
      mockQuery.mockResolvedValueOnce({
        rows: [],
        command: 'DELETE',
        rowCount: 0,
        oid: 0,
        fields: [],
      });

      // 3. UPDATE last_synced_at
      mockQuery.mockResolvedValueOnce({
        rows: [],
        command: 'UPDATE',
        rowCount: 1,
        oid: 0,
        fields: [],
      });

      await handleSyncCalendar(task);

      // Verify tokens were decrypted
      expect(mockDecrypt).toHaveBeenCalledWith('encrypted-access');
      expect(mockDecrypt).toHaveBeenCalledWith('encrypted-refresh');

      // Verify stale windows were deleted
      expect(mockQuery).toHaveBeenCalledWith(
        expect.stringContaining('DELETE FROM availability_windows'),
        [task.userId],
      );

      // Verify last_synced_at was updated
      expect(mockQuery).toHaveBeenCalledWith(
        expect.stringContaining('UPDATE calendar_integrations'),
        [task.userId],
      );
    });
  });

  describe('fetchFreeBusy', () => {
    it('returns empty array when no busy blocks', async () => {
      const mockCalendar = google.calendar({ version: 'v3' });
      (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
        data: { calendars: { primary: { busy: [] } } },
      });

      const result = await fetchFreeBusy('test-access-token');
      expect(result).toEqual([]);
    });

    it('returns empty array when calendars is null', async () => {
      const mockCalendar = google.calendar({ version: 'v3' });
      (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
        data: { calendars: null },
      });

      const result = await fetchFreeBusy('test-access-token');
      expect(result).toEqual([]);
    });

    it('filters out blocks with missing start or end', async () => {
      const mockCalendar = google.calendar({ version: 'v3' });
      (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
        data: {
          calendars: {
            primary: {
              busy: [
                { start: '2026-04-05T10:00:00Z', end: '2026-04-05T11:00:00Z' },
                { start: '2026-04-05T14:00:00Z' }, // missing end
                { end: '2026-04-05T16:00:00Z' }, // missing start
              ],
            },
          },
        },
      });

      const result = await fetchFreeBusy('test-access-token');
      expect(result).toHaveLength(1);
      expect(result[0]).toEqual({
        start: '2026-04-05T10:00:00Z',
        end: '2026-04-05T11:00:00Z',
      });
    });

    it('returns all valid busy blocks', async () => {
      const mockCalendar = google.calendar({ version: 'v3' });
      (mockCalendar.freebusy.query as jest.Mock).mockResolvedValueOnce({
        data: {
          calendars: {
            primary: {
              busy: [
                { start: '2026-04-05T10:00:00Z', end: '2026-04-05T11:00:00Z' },
                { start: '2026-04-05T14:00:00Z', end: '2026-04-05T15:30:00Z' },
                { start: '2026-04-06T09:00:00Z', end: '2026-04-06T10:00:00Z' },
              ],
            },
          },
        },
      });

      const result = await fetchFreeBusy('test-access-token');
      expect(result).toHaveLength(3);
    });
  });
});
