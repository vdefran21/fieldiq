/**
 * Availability window helpers for FieldIQ Bruno tests.
 */

const { postResource, deleteResource, getCreateAvailabilityBody } = require('../resource-utils.js');
const { getActiveUserTeamVar } = require('./team-helpers.js');

/**
 * Create an availability window with optional overrides.
 * Stores latestAvailabilityId and availabilityId in Bruno collection variables.
 *
 * Accepts either `(teamId, overrides)` or a single object that contains `teamId`
 * plus any body overrides.
 *
 * @param {string|object} teamIdOrOverrides - Team UUID or an overrides object with teamId
 * @param {object} overrides - Fields to override in the default availability body
 * @param {object} [options] - Optional request settings
 * @param {string} [options.baseUrl] - Override base URL for cross-instance setup
 * @returns {object} - Axios response from POST /users/me/availability
 */
async function createAvailabilityWith(teamIdOrOverrides, overrides = {}, options = {}) {
  const userTeamVar = getActiveUserTeamVar();
  const latestTeamId = userTeamVar ? bru.getVar(userTeamVar) : bru.getVar('latestTeamId');
  const useObjectStyle = teamIdOrOverrides && typeof teamIdOrOverrides === 'object' && !Array.isArray(teamIdOrOverrides);
  const baseUrl = useObjectStyle
    ? teamIdOrOverrides.baseUrl || options.baseUrl
    : overrides.baseUrl || options.baseUrl;
  const tid = useObjectStyle
    ? teamIdOrOverrides.teamId || latestTeamId
    : teamIdOrOverrides || latestTeamId;
  if (!tid) throw new Error('No teamId provided and no latestTeamId set for the active user');

  const bodyOverrides = useObjectStyle
    ? { ...teamIdOrOverrides }
    : { ...overrides };
  delete bodyOverrides.teamId;
  delete bodyOverrides.baseUrl;

  const body = getCreateAvailabilityBody(tid, bodyOverrides);
  const resp = await postResource('users/me/availability', body, baseUrl);
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
