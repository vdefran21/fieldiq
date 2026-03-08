/**
 * Team creation and management helpers for FieldIQ Bruno tests.
 */

const { postResource, getCreateTeamBody } = require('../resource-utils.js');

/**
 * Get the cache key for the active user's latest team.
 *
 * @returns {string|null} User-scoped Bruno variable name, or null when no user is active
 */
function getActiveUserTeamVar() {
  const activeUser = bru.getVar('activeUser');
  if (!activeUser) return null;

  // Bruno variable names only allow alpha-numeric characters plus "-", "_", "."
  const sanitizedUser = String(activeUser).replace(/[^A-Za-z0-9._-]/g, '_');
  return `latestTeamId.${sanitizedUser}`;
}

/**
 * Create a team with optional overrides.
 * Stores latestTeamId and teamId in Bruno collection variables.
 *
 * @param {object} overrides - Fields to override in the default team body
 * @param {object} [options] - Optional request settings
 * @param {string} [options.baseUrl] - Override base URL for cross-instance setup
 * @returns {object} - Axios response from POST /teams
 */
async function createTeamWith(overrides = {}, options = {}) {
  const body = getCreateTeamBody(overrides);
  const resp = await postResource('teams', body, options.baseUrl);
  const id = resp.data.id;
  if (id) {
    const userTeamVar = getActiveUserTeamVar();
    bru.setVar('latestTeamId', id);
    bru.setVar('teamId', id);
    if (userTeamVar) {
      bru.setVar(userTeamVar, id);
    }
    console.log('Created team id:', id, '- name:', resp.data.name);
  }
  return resp;
}

/**
 * Ensure a team exists. Reuses the active user's latest team if already set.
 */
async function ensureTeam(overrides = {}, options = {}) {
  const userTeamVar = getActiveUserTeamVar();
  const existing = userTeamVar ? bru.getVar(userTeamVar) : bru.getVar('latestTeamId');
  if (existing) return { id: existing };
  const resp = await createTeamWith(overrides, options);
  return { id: userTeamVar ? bru.getVar(userTeamVar) : bru.getVar('latestTeamId'), response: resp };
}

/**
 * Add a member to a team.
 *
 * @param {string} teamId - Team UUID
 * @param {string} userId - User UUID to add
 * @param {string} role - 'manager' | 'coach' | 'parent'
 * @param {string} [playerName] - Optional player name for roster
 */
async function addTeamMember(teamId, userId, role, playerName) {
  const body = { userId, role };
  if (playerName) body.playerName = playerName;
  return postResource(`teams/${teamId}/members`, body);
}

module.exports = { createTeamWith, ensureTeam, addTeamMember, getActiveUserTeamVar };
