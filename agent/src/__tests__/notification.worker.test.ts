import {
  getRegisteredDevices,
  handleSendNotification,
  NotificationTask,
} from '../workers/notification.worker';

jest.mock('../db', () => ({
  query: jest.fn(),
}));

import { query } from '../db';

const mockQuery = query as jest.MockedFunction<typeof query>;

describe('notification.worker', () => {
  const task: NotificationTask = {
    taskType: 'SEND_NOTIFICATION',
    notificationType: 'negotiation_update',
    teamIds: ['550e8400-e29b-41d4-a716-446655440000'],
    eventName: 'match_found',
    status: 'pending_approval',
    sessionId: '660e8400-e29b-41d4-a716-446655440001',
  };

  beforeEach(() => {
    jest.clearAllMocks();
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

  it('logs one delivery attempt per resolved device', async () => {
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
    expect(consoleSpy).toHaveBeenCalledTimes(2);
    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('type=negotiation_update'),
    );

    consoleSpy.mockRestore();
  });

  it('returns zero when the task has no target teams', async () => {
    const delivered = await handleSendNotification({ ...task, teamIds: [] });

    expect(delivered).toBe(0);
    expect(mockQuery).not.toHaveBeenCalled();
  });
});
