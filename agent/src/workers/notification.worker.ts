import { query } from '../db';

/**
 * Notification task dispatched from the backend for push-style updates.
 *
 * Phase 1 keeps the payload intentionally small and backend-driven. The worker resolves
 * device tokens from the shared database and logs a delivery attempt per device. A real
 * Expo integration can replace the logging transport without changing the task contract.
 */
export interface NotificationTask {
  /** Task discriminator used by the SQS dispatcher. */
  taskType: 'SEND_NOTIFICATION';
  /** Notification category for routing and logging. */
  notificationType: 'negotiation_update' | 'event_created';
  /** Team IDs whose active members should receive this message. */
  teamIds: string[];
  /** Human-readable event key emitted by the backend transition. */
  eventName?: string;
  /** Negotiation session UUID when applicable. */
  sessionId?: string;
  /** Event UUID when applicable. */
  eventId?: string;
  /** Event title or summary for display. */
  title?: string;
  /** Event start time in ISO 8601 format. */
  startsAt?: string;
  /** Event venue, if available. */
  location?: string;
  /** Relative iCalendar download URL for confirmed games. */
  icsUrl?: string;
  /** Negotiation status after the triggering transition. */
  status?: string;
}

interface DeviceRow {
  expo_push_token: string;
  user_id: string;
}

/**
 * Resolves device registrations for all active members of the given teams.
 *
 * @param teamIds UUID strings for teams whose members should be notified.
 * @returns Distinct Expo push tokens with owning user IDs.
 */
export async function getRegisteredDevices(teamIds: string[]): Promise<DeviceRow[]> {
  const result = await query(
    `
      SELECT DISTINCT ud.expo_push_token, ud.user_id
      FROM user_devices ud
      INNER JOIN team_members tm ON tm.user_id = ud.user_id
      WHERE tm.team_id = ANY($1::uuid[])
        AND tm.is_active = true
    `,
    [teamIds],
  );
  return result.rows as DeviceRow[];
}

/**
 * Handles a notification task by resolving recipients and emitting delivery attempts.
 *
 * The current transport is structured logging, which is enough to validate the queue
 * contract and target audience end-to-end before adding Expo's API client.
 *
 * @param task Parsed notification task from SQS.
 * @returns Number of devices targeted for delivery.
 */
export async function handleSendNotification(task: NotificationTask): Promise<number> {
  if (!task.teamIds.length) {
    return 0;
  }

  const devices = await getRegisteredDevices(task.teamIds);
  const summary = buildSummary(task);

  devices.forEach((device) => {
    console.log(
      `Notification delivery attempt: type=${task.notificationType} user=${device.user_id} token=${device.expo_push_token.slice(0, 20)} summary="${summary}"`,
    );
  });

  return devices.length;
}

/**
 * Creates a concise message summary for logs and future transport adapters.
 *
 * @param task Notification task to summarize.
 * @returns One-line message summary.
 */
function buildSummary(task: NotificationTask): string {
  if (task.notificationType === 'event_created') {
    return `${task.title ?? 'Scheduled event'} at ${task.startsAt ?? 'TBD'}`;
  }

  return `${task.eventName ?? 'negotiation_update'} (${task.status ?? 'unknown'})`;
}
