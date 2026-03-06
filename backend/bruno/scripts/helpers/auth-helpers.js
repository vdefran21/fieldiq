/**
 * High-level auth helpers for FieldIQ Bruno tests.
 *
 * FieldIQ uses OTP-based passwordless auth. In dev, +1555* phone numbers
 * bypass rate limiting and always accept OTP "000000".
 *
 * Since FieldIQ doesn't have role-based users (yet), loginAs simply
 * authenticates with a given phone number. Multiple users can be created
 * by using different +1555* phone numbers.
 */

const utils = require('../utils.js');
const { login } = require('../auth-helpers.js');

// Phone number mapping for different test users.
// All +1555* numbers bypass OTP rate limiting and use code "000000".
const USER_PHONES = {
  'manager-a': '+15551234567',
  'manager-b': '+15559876543',
  'coach': '+15551112222',
  'parent': '+15553334444',
};

/**
 * Authenticate as a named test user.
 * Stores accessToken, refreshToken, userId in Bruno collection variables.
 *
 * @param {string} userAlias - Key from USER_PHONES (e.g. 'manager-a', 'parent')
 */
async function loginAs(userAlias) {
  utils.initEnv();
  const phone = USER_PHONES[userAlias];
  if (!phone) throw new Error(`Unknown user alias: ${userAlias}. Valid: ${Object.keys(USER_PHONES).join(', ')}`);

  // Clear existing token to force re-auth as this user
  const currentUser = bru.getVar('activeUser');
  if (currentUser === userAlias) {
    // Already logged in as this user, verify token still valid
    const existingToken = bru.getVar('accessToken');
    if (existingToken) {
      console.log(`Already logged in as ${userAlias}`);
      return;
    }
  }

  bru.setVar('accessToken', '');
  const authData = await login(phone);
  bru.setVar('activeUser', userAlias);
  console.log(`Logged in as ${userAlias} (userId: ${authData.user.id})`);
  return authData;
}

/**
 * Switch to a different test user.
 */
async function switchUser(toAlias) {
  const current = bru.getVar('activeUser');
  if (current === toAlias) {
    console.log(`Already logged in as ${toAlias}`);
    return;
  }
  // Clear current token and login as new user
  bru.setVar('accessToken', '');
  bru.setVar('activeUser', '');
  await loginAs(toAlias);
}

module.exports = { loginAs, switchUser, USER_PHONES };
