/**
 * Event creation and RSVP helpers for FieldIQ Bruno tests.
 */

const { postResource, patchResource, getCreateEventBody } = require('../resource-utils.js');

/**
 * Create an event on a team with optional overrides.
 * Stores latestEventId and eventId in Bruno collection variables.
 *
 * @param {string} teamId - Team UUID
 * @param {object} overrides - Fields to override in the default event body
 * @returns {object} - Axios response from POST /teams/{teamId}/events
 */
async function createEventWith(teamId, overrides = {}) {
  const tid = teamId || bru.getVar('latestTeamId');
  if (!tid) throw new Error('No teamId provided and no latestTeamId set');

  const body = getCreateEventBody(overrides);
  const resp = await postResource(`teams/${tid}/events`, body);
  const id = resp.data.id;
  if (id) {
    bru.setVar('latestEventId', id);
    bru.setVar('eventId', id);
    console.log('Created event id:', id, '- title:', resp.data.title);
  }
  return resp;
}

/**
 * Ensure an event exists. Reuses latestEventId if already set.
 */
async function ensureEvent(teamId, overrides = {}) {
  const existing = bru.getVar('latestEventId');
  if (existing) return { id: existing };
  const resp = await createEventWith(teamId, overrides);
  return { id: bru.getVar('latestEventId'), response: resp };
}

/**
 * RSVP to an event.
 *
 * @param {string} eventId - Event UUID
 * @param {string} status - 'going' | 'not_going' | 'maybe'
 */
async function respondToEvent(eventId, status) {
  const eid = eventId || bru.getVar('latestEventId');
  if (!eid) throw new Error('No eventId provided and no latestEventId set');
  return postResource(`events/${eid}/respond`, { status });
}

/**
 * Update an event (PATCH semantics).
 */
async function updateEvent(eventId, updates) {
  const eid = eventId || bru.getVar('latestEventId');
  if (!eid) throw new Error('No eventId provided and no latestEventId set');
  return patchResource(`events/${eid}`, updates);
}

module.exports = { createEventWith, ensureEvent, respondToEvent, updateEvent };
