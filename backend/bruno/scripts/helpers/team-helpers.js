/**
 * Team creation and management helpers for FieldIQ Bruno tests.
 */

const { postResource, getCreateTeamBody } = require('../resource-utils.js');

/**
 * Create a team with optional overrides.
 * Stores latestTeamId and teamId in Bruno collection variables.
 *
 * @param {object} overrides - Fields to override in the default team body
 * @returns {object} - Axios response from POST /teams
 */
async function createTeamWith(overrides = {}) {
  const body = getCreateTeamBody(overrides);
  const resp = await postResource('teams', body);
  const id = resp.data.id;
  if (id) {
    bru.setVar('latestTeamId', id);
    bru.setVar('teamId', id);
    console.log('Created team id:', id, '- name:', resp.data.name);
  }
  return resp;
}

/**
 * Ensure a team exists. Reuses latestTeamId if already set.
 */
async function ensureTeam(overrides = {}) {
  const existing = bru.getVar('latestTeamId');
  if (existing) return { id: existing };
  const resp = await createTeamWith(overrides);
  return { id: bru.getVar('latestTeamId'), response: resp };
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

module.exports = { createTeamWith, ensureTeam, addTeamMember };
