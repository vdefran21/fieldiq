/**
 * Shared Bruno test helpers for FieldIQ API tests.
 *
 * Central re-export module — .bru files should only need:
 *   const { loginAs, createTeamWith, ... } = require('./scripts/test-helpers.js');
 *
 * Conventions:
 * - All helpers use Bearer token auth via bru.getVar('accessToken')
 * - loginAs(alias) authenticates as a named test user (see helpers/auth-helpers.js)
 * - create*With(overrides) creates a resource with Faker defaults + overrides
 * - ensure*() reuses an existing resource or creates one if needed
 * - Bruno collection variables used:
 *     accessToken, refreshToken, userId, activeUser,
 *     latestTeamId, latestEventId, latestAvailabilityId
 */

const { getResource, postResource, patchResource, deleteResource } = require('./resource-utils.js');
const { loginAs, switchUser } = require('./helpers/auth-helpers.js');
const { createTeamWith, ensureTeam, addTeamMember } = require('./helpers/team-helpers.js');
const { createEventWith, ensureEvent, respondToEvent, updateEvent } = require('./helpers/event-helpers.js');
const { createAvailabilityWith, ensureAvailability, deleteAvailability } = require('./helpers/availability-helpers.js');

module.exports = {
  // HTTP
  getResource,
  postResource,
  patchResource,
  deleteResource,
  // auth
  loginAs,
  switchUser,
  // teams
  createTeamWith,
  ensureTeam,
  addTeamMember,
  // events
  createEventWith,
  ensureEvent,
  respondToEvent,
  updateEvent,
  // availability
  createAvailabilityWith,
  ensureAvailability,
  deleteAvailability,
};
