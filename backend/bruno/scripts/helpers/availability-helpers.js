/**
 * Availability window helpers for FieldIQ Bruno tests.
 */

const { postResource, deleteResource, getCreateAvailabilityBody } = require('../resource-utils.js');

/**
 * Create an availability window with optional overrides.
 * Stores latestAvailabilityId and availabilityId in Bruno collection variables.
 *
 * @param {string} teamId - Team UUID
 * @param {object} overrides - Fields to override in the default availability body
 * @returns {object} - Axios response from POST /users/me/availability
 */
async function createAvailabilityWith(teamId, overrides = {}) {
  const tid = teamId || bru.getVar('latestTeamId');
  if (!tid) throw new Error('No teamId provided and no latestTeamId set');

  const body = getCreateAvailabilityBody(tid, overrides);
  const resp = await postResource('users/me/availability', body);
  const id = resp.data.id;
  if (id) {
    bru.setVar('latestAvailabilityId', id);
    bru.setVar('availabilityId', id);
    console.log('Created availability window id:', id);
  }
  return resp;
}

/**
 * Ensure an availability window exists. Reuses latestAvailabilityId if already set.
 */
async function ensureAvailability(teamId, overrides = {}) {
  const existing = bru.getVar('latestAvailabilityId');
  if (existing) return { id: existing };
  const resp = await createAvailabilityWith(teamId, overrides);
  return { id: bru.getVar('latestAvailabilityId'), response: resp };
}

/**
 * Delete an availability window.
 */
async function deleteAvailability(windowId) {
  const wid = windowId || bru.getVar('latestAvailabilityId');
  if (!wid) throw new Error('No windowId provided and no latestAvailabilityId set');
  return deleteResource(`users/me/availability/${wid}`);
}

module.exports = { createAvailabilityWith, ensureAvailability, deleteAvailability };
