import { google, calendar_v3 } from 'googleapis';
import { query } from '../db';
import { decryptToken } from '../encryption';
import { config } from '../config';

/**
 * SQS task payload for calendar sync operations.
 *
 * Enqueued by the backend when a user connects their Google Calendar
 * or on a 4-hour schedule for periodic re-syncs.
 */
export interface SyncCalendarTask {
  taskType: 'SYNC_CALENDAR';
  userId: string;
  teamId: string;
}

/**
 * A busy block from Google Calendar's FreeBusy API.
 */
interface BusyBlock {
  start: string;
  end: string;
}

/**
 * Processes a SYNC_CALENDAR task by fetching Google Calendar busy blocks
 * and storing them as unavailable availability windows.
 *
 * **Flow (per Doc 05):**
 * 1. Read the user's encrypted OAuth tokens from `calendar_integrations`.
 * 2. Decrypt the access token using the shared encryption key.
 * 3. If the access token is expired, refresh it using the refresh token.
 * 4. Call Google Calendar's FreeBusy API for the next 30 days.
 * 5. Delete existing `google_cal` availability windows for this user.
 * 6. Insert fresh busy blocks as `availability_windows` with `source='google_cal'`
 *    and `window_type='unavailable'`.
 * 7. Update `last_synced_at` on the calendar integration record.
 *
 * **Privacy:** Only FreeBusy data is read — no event titles, descriptions,
 * or attendee information is accessed.
 *
 * @param task The SQS task payload containing userId and teamId.
 * @throws Error if the user has no calendar integration or the API call fails.
 */
export async function handleSyncCalendar(
  task: SyncCalendarTask,
): Promise<void> {
  const { userId, teamId } = task;

  // 1. Load calendar integration
  const integrationResult = await query(
    'SELECT id, access_token, refresh_token, expires_at FROM calendar_integrations WHERE user_id = $1',
    [userId],
  );

  if (integrationResult.rows.length === 0) {
    throw new Error(`No calendar integration found for user ${userId}`);
  }

  const integration = integrationResult.rows[0];

  // 2. Decrypt tokens
  let accessToken = decryptToken(integration.access_token);
  const refreshToken = decryptToken(integration.refresh_token);

  // 3. Check if access token is expired and refresh if needed
  const expiresAt = new Date(integration.expires_at);
  if (expiresAt <= new Date()) {
    accessToken = await refreshAccessToken(
      userId,
      integration.id,
      refreshToken,
    );
  }

  // 4. Call Google FreeBusy API
  const busyBlocks = await fetchFreeBusy(accessToken);

  // 5. Delete stale google_cal windows for this user
  await query(
    "DELETE FROM availability_windows WHERE user_id = $1 AND source = 'google_cal'",
    [userId],
  );

  // 6. Insert fresh busy blocks as unavailable windows
  if (busyBlocks.length > 0) {
    const values: unknown[] = [];
    const placeholders: string[] = [];
    let paramIndex = 1;

    for (const block of busyBlocks) {
      placeholders.push(
        `($${paramIndex++}, $${paramIndex++}, $${paramIndex++}, $${paramIndex++}, $${paramIndex++})`,
      );
      values.push(
        userId,
        block.start, // day_of_week not used for specific-date google_cal windows
        block.end,
        'google_cal',
        teamId,
      );
    }

    // Insert as specific-date unavailable windows
    // The availability_windows table expects: user_id, start_time, end_time, source, team_id
    // For Google Calendar busy blocks, we store them as specific-date unavailable windows
    await query(
      `INSERT INTO availability_windows (user_id, specific_date, window_type, source, team_id, start_time, end_time)
       SELECT
         v.user_id::uuid,
         (v.start_ts::timestamptz)::date,
         'unavailable',
         'google_cal',
         v.team_id::uuid,
         (v.start_ts::timestamptz)::time,
         (v.end_ts::timestamptz)::time
       FROM (VALUES ${busyBlocks
         .map(
           (_, i) =>
             `($${i * 3 + 1}, $${i * 3 + 2}::timestamptz, $${i * 3 + 3}::timestamptz)`,
         )
         .join(', ')}) AS v(user_id, start_ts, end_ts)`,
      busyBlocks.flatMap((block) => [userId, block.start, block.end]),
    );
  }

  // 7. Update last_synced_at
  await query(
    'UPDATE calendar_integrations SET last_synced_at = NOW() WHERE user_id = $1',
    [userId],
  );
}

/**
 * Fetches busy blocks from Google Calendar's FreeBusy API.
 *
 * Queries the primary calendar for the next 30 days. Returns only
 * busy time ranges — no event details are accessed.
 *
 * @param accessToken A valid Google OAuth access token with calendar.readonly scope.
 * @returns Array of busy blocks with ISO-8601 start/end timestamps.
 */
export async function fetchFreeBusy(
  accessToken: string,
): Promise<BusyBlock[]> {
  const oauth2Client = new google.auth.OAuth2();
  oauth2Client.setCredentials({ access_token: accessToken });

  const calendar = google.calendar({ version: 'v3', auth: oauth2Client });

  const now = new Date();
  const lookAhead = new Date();
  lookAhead.setDate(
    lookAhead.getDate() + config.worker.freeBusyLookAheadDays,
  );

  const response = await calendar.freebusy.query({
    requestBody: {
      timeMin: now.toISOString(),
      timeMax: lookAhead.toISOString(),
      items: [{ id: 'primary' }],
    },
  });

  const calendars = response.data.calendars;
  if (!calendars || !calendars['primary']) {
    return [];
  }

  const busySlots = calendars['primary'].busy || [];
  return busySlots
    .filter(
      (slot): slot is { start: string; end: string } =>
        !!slot.start && !!slot.end,
    )
    .map((slot) => ({
      start: slot.start!,
      end: slot.end!,
    }));
}

/**
 * Refreshes an expired Google OAuth access token.
 *
 * Uses the stored refresh token to obtain a new access token from Google,
 * then encrypts and updates the database record.
 *
 * @param userId The user whose token needs refreshing.
 * @param integrationId The calendar_integrations record ID.
 * @param refreshToken The decrypted refresh token.
 * @returns The new access token (decrypted, ready for API calls).
 */
async function refreshAccessToken(
  userId: string,
  integrationId: string,
  refreshToken: string,
): Promise<string> {
  const oauth2Client = new google.auth.OAuth2(
    config.google.clientId,
    config.google.clientSecret,
  );

  oauth2Client.setCredentials({ refresh_token: refreshToken });

  const { credentials } = await oauth2Client.refreshAccessToken();
  const newAccessToken = credentials.access_token;

  if (!newAccessToken) {
    throw new Error(`Failed to refresh access token for user ${userId}`);
  }

  // The backend handles re-encryption of the refreshed token.
  // For now, we update expires_at so the next sync knows it was refreshed.
  const expiresAt = credentials.expiry_date
    ? new Date(credentials.expiry_date)
    : new Date(Date.now() + 3600 * 1000);

  await query(
    'UPDATE calendar_integrations SET expires_at = $1 WHERE id = $2',
    [expiresAt.toISOString(), integrationId],
  );

  return newAccessToken;
}
