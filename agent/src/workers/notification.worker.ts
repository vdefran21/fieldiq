import { config } from '../config';
import { query } from '../db';

/**
 * Notification task dispatched from the backend for push-style updates.
 *
 * Phase 1 keeps the payload intentionally small and backend-driven. The worker resolves
 * device tokens from the shared database and delivers an Expo push message per device.
 */
export interface NotificationTask {
  /** Task discriminator used by the SQS dispatcher. */
  taskType: 'SEND_NOTIFICATION';
  /** Notification category for routing and delivery copy. */
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
  /** Agreed negotiation start time when a match exists. */
  agreedStartsAt?: string;
  /** Agreed negotiation end time when a match exists. */
  agreedEndsAt?: string;
  /** Agreed venue when a match exists. */
  agreedLocation?: string;
}

interface DeviceRow {
  /** Expo push token registered by the mobile client for this device. */
  expo_push_token: string;
  /** User UUID that owns the device registration. */
  user_id: string;
}

/**
 * Expo push payload shape submitted by the worker.
 */
interface ExpoPushMessage {
  /** Expo push token identifying the target device. */
  to: string;
  /** Notification title rendered by the mobile OS. */
  title: string;
  /** Notification body rendered by the mobile OS. */
  body: string;
  /** Default push sound requested for the delivered notification. */
  sound: 'default';
  /** Deep-link and metadata payload consumed by the mobile client. */
  data: Record<string, string>;
}

/**
 * Partial Expo push ticket returned for each submitted message.
 */
interface ExpoPushTicket {
  /** Delivery acceptance status reported by Expo. */
  status: 'ok' | 'error';
  /** Expo ticket ID when the message was accepted. */
  id?: string;
  /** Human-readable error message when the request or message failed. */
  message?: string;
  /** Structured Expo error details, if present. */
  details?: { error?: string };
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
 * Handles a notification task by resolving recipients and pushing Expo messages.
 *
 * @param task Parsed notification task from SQS.
 * @returns Number of devices targeted for delivery.
 */
export async function handleSendNotification(task: NotificationTask): Promise<number> {
  if (!task.teamIds.length) {
    return 0;
  }

  const devices = await getRegisteredDevices(task.teamIds);
  if (!devices.length) {
    return 0;
  }

  const messages = buildExpoMessages(task, devices);
  const tickets = await sendExpoPushMessages(messages);

  tickets.forEach((ticket, index) => {
    const device = devices[index];
    if (ticket.status === 'ok') {
      console.log(
        `Notification delivered: type=${task.notificationType} user=${device.user_id} sessionId=${task.sessionId ?? 'n/a'} eventId=${task.eventId ?? 'n/a'} ticket=${ticket.id ?? 'n/a'}`,
      );
      return;
    }

    console.error(
      `Notification failed: type=${task.notificationType} user=${device.user_id} token=${device.expo_push_token.slice(0, 20)} error=${ticket.details?.error ?? ticket.message ?? 'unknown'}`,
    );
  });

  return devices.length;
}

/**
 * Converts one backend notification task into per-device Expo push payloads.
 *
 * @param task Backend-owned notification task payload.
 * @param devices Target devices resolved from the database.
 * @returns One Expo message per device.
 */
function buildExpoMessages(task: NotificationTask, devices: DeviceRow[]): ExpoPushMessage[] {
  const content = buildContent(task);

  return devices.map((device) => ({
    to: device.expo_push_token,
    title: content.title,
    body: content.body,
    sound: 'default',
    data: {
      notificationType: task.notificationType,
      sessionId: task.sessionId ?? '',
      eventId: task.eventId ?? '',
      status: task.status ?? '',
      icsUrl: task.icsUrl ?? '',
    },
  }));
}

/**
 * Sends Expo push messages using the current configured transport endpoint.
 *
 * @param messages Expo push messages to send.
 * @returns Push tickets returned by Expo, in the same order as the request.
 * @throws Error When Expo rejects the HTTP request or returns a transport-level failure.
 */
async function sendExpoPushMessages(messages: ExpoPushMessage[]): Promise<ExpoPushTicket[]> {
  const response = await fetch(config.expo.pushEndpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(config.expo.accessToken ? { Authorization: `Bearer ${config.expo.accessToken}` } : {}),
    },
    body: JSON.stringify(messages),
  });

  const payload = (await response.json()) as { data?: ExpoPushTicket[]; errors?: Array<{ message?: string }> };
  if (!response.ok) {
    const reason = payload.errors?.map((error) => error.message).filter(Boolean).join(', ') || response.statusText;
    throw new Error(`Expo push request failed: ${reason}`);
  }

  return payload.data ?? [];
}

/**
 * Builds the user-facing title and body for a notification payload.
 *
 * The backend decides when to enqueue a notification and which status transition
 * occurred. This helper translates that backend event into concise copy that fits
 * native push constraints without requiring the worker to know app screen state.
 *
 * @param task Notification task to summarize.
 * @returns Title/body copy for Expo push delivery.
 */
function buildContent(task: NotificationTask): { title: string; body: string } {
  if (task.notificationType === 'event_created') {
    return {
      title: task.title ?? 'Game scheduled',
      body: `${task.startsAt ?? 'A scheduled event'}${task.location ? ` at ${task.location}` : ''}`,
    };
  }

  if (task.status === 'pending_approval' && task.agreedStartsAt && task.agreedEndsAt) {
    return {
      title: 'Match found',
      body: `${task.agreedStartsAt} to ${task.agreedEndsAt}${task.agreedLocation ? ` at ${task.agreedLocation}` : ''}`,
    };
  }

  if (task.status === 'confirmed') {
    return {
      title: 'Negotiation confirmed',
      body: 'Both managers confirmed the game slot.',
    };
  }

  return {
    title: 'Negotiation update',
    body: `${task.eventName ?? 'negotiation_update'} (${task.status ?? 'unknown'})`,
  };
}
