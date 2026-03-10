#!/usr/bin/env node

/**
 * Seeds deterministic recurring demo availability on the local two-instance stack.
 *
 * This script uses only the public HTTP API so the seeded demo path exercises the
 * same auth, team, availability, and scheduling contracts as the mobile client.
 * It is intended for explicit local-demo setup, not automatic startup seeding.
 */

const DEFAULT_INSTANCES = [
  {
    label: 'instance-a',
    baseUrl: 'http://localhost:8080',
    phone: '+15551234567',
    teamName: 'Demo Team A',
    sport: 'soccer',
    ageGroup: 'U10',
    availability: [
      { dayOfWeek: 6, startTime: '09:00', endTime: '12:00' },
      { dayOfWeek: 2, startTime: '18:00', endTime: '20:00' },
    ],
  },
  {
    label: 'instance-b',
    baseUrl: 'http://localhost:8081',
    phone: '+15559876543',
    teamName: 'Demo Team B',
    sport: 'soccer',
    ageGroup: 'U10',
    availability: [
      { dayOfWeek: 6, startTime: '10:00', endTime: '13:00' },
      { dayOfWeek: 4, startTime: '18:00', endTime: '20:00' },
    ],
  },
];

const EXPECTED_OVERLAP = {
  dayOfWeek: 6,
  startTime: '10:00',
  endTime: '12:00',
};

/**
 * Parsed script arguments.
 */
const options = parseArgs(process.argv.slice(2));

/**
 * Entry point for the seed workflow.
 */
async function main() {
  const instances = resolveInstances();
  const results = [];

  for (const instance of instances) {
    const tokens = await login(instance);
    const team = await ensureTeam(instance, tokens.accessToken);

    if (options.reset) {
      await resetManualAvailability(instance, tokens.accessToken, team.id);
    }

    const seededWindows = await ensureAvailability(instance, tokens.accessToken, team.id);
    const verification = await verifyScheduling(instance, tokens.accessToken, team.id);

    results.push({
      ...instance,
      team,
      seededWindows,
      verification,
    });
  }

  const mutualOverlap = verifyCrossInstanceOverlap(results);
  printSummary(results);
  console.log('');
  console.log(`Verified mutual overlap: ${mutualOverlap.startsAt} -> ${mutualOverlap.endsAt}`);
}

/**
 * Parses command-line flags for the seed script.
 *
 * Supported flags:
 * - `--reset`: clear existing manual windows for the seeded demo team first
 * - `--instance-a-url=...`: override instance A base URL
 * - `--instance-b-url=...`: override instance B base URL
 *
 * @param args Raw CLI arguments excluding `node` and script path.
 * @returns Parsed option values for the current invocation.
 */
function parseArgs(args) {
  return {
    reset: args.includes('--reset'),
    instanceAUrl: getArgValue(args, '--instance-a-url'),
    instanceBUrl: getArgValue(args, '--instance-b-url'),
  };
}

/**
 * Extracts a `--key=value` style flag from the CLI.
 *
 * @param args Raw CLI arguments.
 * @param flagPrefix Flag prefix to search for.
 * @returns The string value after `=`, or `null` when absent.
 */
function getArgValue(args, flagPrefix) {
  const match = args.find((arg) => arg.startsWith(`${flagPrefix}=`));
  return match ? match.slice(flagPrefix.length + 1) : null;
}

/**
 * Resolves per-instance configuration, allowing environment or flag overrides.
 *
 * @returns Instance definitions for the local demo seeding workflow.
 */
function resolveInstances() {
  return DEFAULT_INSTANCES.map((instance) => {
    const envKey = instance.label === 'instance-a' ? 'FIELDIQ_INSTANCE_A_URL' : 'FIELDIQ_INSTANCE_B_URL';
    const flagValue = instance.label === 'instance-a' ? options.instanceAUrl : options.instanceBUrl;

    return {
      ...instance,
      baseUrl: flagValue || process.env[envKey] || instance.baseUrl,
    };
  });
}

/**
 * Performs the dev OTP login flow against one backend instance.
 *
 * @param instance Seed target metadata.
 * @returns Access and refresh tokens returned by the backend auth flow.
 */
async function login(instance) {
  await request(instance.baseUrl, '/auth/request-otp', {
    method: 'POST',
    body: {
      channel: 'sms',
      value: instance.phone,
    },
  });

  const auth = await request(instance.baseUrl, '/auth/verify-otp', {
    method: 'POST',
    body: {
      channel: 'sms',
      value: instance.phone,
      otp: '000000',
    },
  });

  return {
    accessToken: auth.accessToken,
    refreshToken: auth.refreshToken,
  };
}

/**
 * Ensures the deterministic demo team exists on the target instance.
 *
 * Reuses an existing team by exact name if present; otherwise creates it through
 * the normal team API so the seeded demo path stays aligned with the app flow.
 *
 * @param instance Seed target metadata.
 * @param accessToken Bearer token for the seeded demo user.
 * @returns Team resource used for availability seeding.
 */
async function ensureTeam(instance, accessToken) {
  const teams = await request(instance.baseUrl, '/teams', {
    headers: authHeaders(accessToken),
  });

  const existing = teams.find((team) => team.name === instance.teamName);
  if (existing) {
    return existing;
  }

  return request(instance.baseUrl, '/teams', {
    method: 'POST',
    headers: authHeaders(accessToken),
    body: {
      name: instance.teamName,
      sport: instance.sport,
      ageGroup: instance.ageGroup,
    },
  });
}

/**
 * Removes manual availability windows owned by the seeded user for the seeded team.
 *
 * This intentionally leaves calendar-derived windows and unrelated teams untouched.
 *
 * @param instance Seed target metadata.
 * @param accessToken Bearer token for the seeded demo user.
 * @param teamId Team that should receive deterministic demo availability.
 */
async function resetManualAvailability(instance, accessToken, teamId) {
  const ownWindows = await request(instance.baseUrl, '/users/me/availability', {
    headers: authHeaders(accessToken),
  });

  const deletions = ownWindows
    .filter((window) => window.teamId === teamId && window.source === 'manual')
    .map((window) =>
      request(instance.baseUrl, `/users/me/availability/${window.id}`, {
        method: 'DELETE',
        headers: authHeaders(accessToken),
        expectNoContent: true,
      }),
    );

  await Promise.all(deletions);
}

/**
 * Ensures the deterministic recurring manual windows exist for the seeded team.
 *
 * Existing matching windows are reused to keep the script idempotent without
 * duplicating recurring blocks on repeated runs.
 *
 * @param instance Seed target metadata.
 * @param accessToken Bearer token for the seeded demo user.
 * @param teamId Team that should receive deterministic demo availability.
 * @returns Manual recurring windows present after the seed run.
 */
async function ensureAvailability(instance, accessToken, teamId) {
  const ownWindows = await request(instance.baseUrl, '/users/me/availability', {
    headers: authHeaders(accessToken),
  });

  const existing = ownWindows.filter((window) => window.teamId === teamId && window.source === 'manual');
  const created = [];

  for (const template of instance.availability) {
    const match = existing.find((window) =>
      window.dayOfWeek === template.dayOfWeek &&
      window.startTime === template.startTime &&
      window.endTime === template.endTime &&
      window.windowType === 'available',
    );

    if (match) {
      created.push(match);
      continue;
    }

    const window = await request(instance.baseUrl, '/users/me/availability', {
      method: 'POST',
      headers: authHeaders(accessToken),
      body: {
        teamId,
        dayOfWeek: template.dayOfWeek,
        startTime: template.startTime,
        endTime: template.endTime,
        windowType: 'available',
      },
    });
    created.push(window);
  }

  return created;
}

/**
 * Verifies that the seeded windows produce scheduling suggestions for the demo team.
 *
 * The check is local to each instance and guards against accidental drift in the
 * seed windows, scheduling endpoint, or timezone assumptions.
 *
 * @param instance Seed target metadata.
 * @param accessToken Bearer token for the seeded demo user.
 * @param teamId Team to verify through the scheduling API.
 * @returns Scheduling verification details for reporting.
 */
async function verifyScheduling(instance, accessToken, teamId) {
  const suggestions = await request(instance.baseUrl, `/teams/${teamId}/suggest-windows`, {
    method: 'POST',
    headers: authHeaders(accessToken),
    body: {
      dateRangeStart: nextDateForDayOfWeek(EXPECTED_OVERLAP.dayOfWeek),
      dateRangeEnd: nextDateForDayOfWeek(EXPECTED_OVERLAP.dayOfWeek, 21),
      durationMinutes: 90,
      preferredDays: [EXPECTED_OVERLAP.dayOfWeek],
    },
  });

  if (!Array.isArray(suggestions) || suggestions.length === 0) {
    throw new Error(`No scheduling suggestions returned for ${instance.label} (${instance.baseUrl})`);
  }

  return {
    suggestions,
    topWindow: suggestions[0],
    count: suggestions.length,
  };
}

/**
 * Confirms that the two seeded instances expose at least one overlapping suggestion.
 *
 * The local per-instance scheduling checks ensure each seeded baseline can generate
 * windows. This cross-instance check closes the loop by verifying the two suggestion
 * sets still share a real mutual slot for the demo negotiation.
 *
 * @param results Completed seed results for the configured instances.
 * @returns The first overlapping suggestion pair rendered as a mutual slot.
 */
function verifyCrossInstanceOverlap(results) {
  if (results.length < 2) {
    throw new Error('Cross-instance overlap verification requires at least two configured instances.');
  }

  const [instanceA, instanceB] = results;
  for (const left of instanceA.verification.suggestions) {
    for (const right of instanceB.verification.suggestions) {
      const overlap = intersectSuggestionWindows(left, right);
      if (overlap) {
        return overlap;
      }
    }
  }

  throw new Error(
    `No mutual overlap found between ${instanceA.label} (${instanceA.baseUrl}) and ${instanceB.label} (${instanceB.baseUrl})`,
  );
}

/**
 * Computes the intersection of two scheduling suggestions.
 *
 * @param left First scheduling suggestion.
 * @param right Second scheduling suggestion.
 * @returns Overlapping slot metadata, or `null` when the suggestions do not overlap.
 */
function intersectSuggestionWindows(left, right) {
  const startsAt = new Date(Math.max(Date.parse(left.startsAt), Date.parse(right.startsAt)));
  const endsAt = new Date(Math.min(Date.parse(left.endsAt), Date.parse(right.endsAt)));

  if (startsAt >= endsAt) {
    return null;
  }

  return {
    startsAt: startsAt.toISOString(),
    endsAt: endsAt.toISOString(),
  };
}

/**
 * Sends one HTTP request and parses the JSON response.
 *
 * @param baseUrl Instance base URL.
 * @param path Relative request path.
 * @param options HTTP method, headers, and body configuration.
 * @returns Parsed JSON payload, or `undefined` for successful 204 responses.
 */
async function request(baseUrl, path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method || 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${options.method || 'GET'} ${baseUrl}${path} failed (${response.status}): ${body || response.statusText}`);
  }

  if (options.expectNoContent || response.status === 204) {
    return undefined;
  }

  const text = await response.text();
  return text ? JSON.parse(text) : undefined;
}

/**
 * Returns bearer auth headers for one request.
 *
 * @param accessToken JWT access token issued by the backend.
 * @returns Authorization header object.
 */
function authHeaders(accessToken) {
  return {
    Authorization: `Bearer ${accessToken}`,
  };
}

/**
 * Computes the next calendar date for the requested FieldIQ day-of-week.
 *
 * FieldIQ uses 0=Sunday through 6=Saturday.
 *
 * @param fieldiqDayOfWeek Target FieldIQ day-of-week.
 * @param daysAhead Maximum range to search forward from today.
 * @returns Date string in YYYY-MM-DD format.
 */
function nextDateForDayOfWeek(fieldiqDayOfWeek, daysAhead = 14) {
  const date = new Date();

  for (let offset = 0; offset <= daysAhead; offset += 1) {
    const candidate = new Date(date);
    candidate.setDate(date.getDate() + offset);
    const candidateDay = candidate.getDay();
    if (candidateDay === fieldiqDayOfWeek) {
      return candidate.toISOString().slice(0, 10);
    }
  }

  throw new Error(`Unable to find day-of-week ${fieldiqDayOfWeek} within ${daysAhead} days`);
}

/**
 * Prints a human-readable summary of the seeded demo state.
 *
 * @param results Seed results for each local instance.
 */
function printSummary(results) {
  console.log('FieldIQ demo availability seed complete.');
  console.log(`Expected mutual overlap: day=${EXPECTED_OVERLAP.dayOfWeek}, ${EXPECTED_OVERLAP.startTime}-${EXPECTED_OVERLAP.endTime}`);

  for (const result of results) {
    console.log('');
    console.log(`[${result.label}] ${result.baseUrl}`);
    console.log(`  demo phone: ${result.phone}`);
    console.log(`  team: ${result.team.name} (${result.team.id})`);
    console.log('  seeded windows:');
    for (const window of result.seededWindows) {
      console.log(`    - dow=${window.dayOfWeek} ${window.startTime}-${window.endTime} (${window.windowType}, ${window.source})`);
    }
    console.log(`  top suggestion: ${result.verification.topWindow.startsAt} -> ${result.verification.topWindow.endsAt} (confidence ${result.verification.topWindow.confidence})`);
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
