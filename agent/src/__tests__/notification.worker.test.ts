import {
  getRegisteredDevices,
  handleSendNotification,
  NotificationTask,
} from '../workers/notification.worker';

/**
 * Unit tests for Expo notification delivery orchestration.
 *
 * The database lookup and Expo transport stay mocked here so the suite can
 * focus on recipient resolution, request construction, and error handling.
 */

jest.mock('../db', () => ({
  query: jest.fn(),
}));

import { query } from '../db';

const mockQuery = query as jest.MockedFunction<typeof query>;

describe('notification.worker', () => {
  /**
   * Representative backend-originated notification payload reused across tests.
   */
  const task: NotificationTask = {
    taskType: 'SEND_NOTIFICATION',
    notificationType: 'negotiation_update',
    teamIds: ['550e8400-e29b-41d4-a716-446655440000'],
    eventName: 'match_found',
    status: 'pending_approval',
    sessionId: '660e8400-e29b-41d4-a716-446655440001',
    agreedStartsAt: '2026-04-05T14:00:00Z',
    agreedEndsAt: '2026-04-05T15:30:00Z',
    agreedLocation: 'Field 3',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    // Default fetch stub models a successful Expo batch request.
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: [
          { status: 'ok', id: 'ticket-1' },
          { status: 'ok', id: 'ticket-2' },
        ],
      }),
    }) as jest.Mock;
  });

  it('loads distinct devices for active team members', async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ expo_push_token: 'ExponentPushToken[abc]', user_id: 'user-1' }],
      command: 'SELECT',
      rowCount: 1,
      oid: 0,
      fields: [],
    });

    const result = await getRegisteredDevices(task.teamIds);

    expect(result).toEqual([{ expo_push_token: 'ExponentPushToken[abc]', user_id: 'user-1' }]);
    expect(mockQuery).toHaveBeenCalledWith(expect.stringContaining('FROM user_devices'), [task.teamIds]);
  });

  it('sends one Expo push message per resolved device', async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [
        { expo_push_token: 'ExponentPushToken[first]', user_id: 'user-1' },
        { expo_push_token: 'ExponentPushToken[second]', user_id: 'user-2' },
      ],
      command: 'SELECT',
      rowCount: 2,
      oid: 0,
      fields: [],
    });

    const consoleSpy = jest.spyOn(console, 'log').mockImplementation();

    const delivered = await handleSendNotification(task);

    expect(delivered).toBe(2);
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('https://exp.host/--/api/v2/push/send'),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
    expect(consoleSpy).toHaveBeenCalledTimes(2);

    consoleSpy.mockRestore();
  });

  it('returns zero when the task has no target teams', async () => {
    const delivered = await handleSendNotification({ ...task, teamIds: [] });

    expect(delivered).toBe(0);
    expect(mockQuery).not.toHaveBeenCalled();
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('throws when Expo rejects the push request', async () => {
    mockQuery.mockResolvedValueOnce({
      rows: [{ expo_push_token: 'ExponentPushToken[first]', user_id: 'user-1' }],
      command: 'SELECT',
      rowCount: 1,
      oid: 0,
      fields: [],
    });
    global.fetch = jest.fn().mockResolvedValue({
      ok: false,
      statusText: 'Bad Request',
      json: async () => ({
        errors: [{ message: 'DeviceNotRegistered' }],
      }),
    }) as jest.Mock;

    await expect(handleSendNotification(task)).rejects.toThrow('Expo push request failed');
  });
});
