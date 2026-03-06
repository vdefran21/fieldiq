/**
 * Low-level auth helpers for FieldIQ OTP-based authentication.
 *
 * FieldIQ uses passwordless OTP auth:
 *   1. POST /auth/request-otp  → sends OTP (dev bypass: +1555* phones get "000000")
 *   2. POST /auth/verify-otp   → returns { accessToken, refreshToken, user }
 *
 * The access token is stored as a Bearer token, not a cookie.
 */

const axios = require('axios');

/**
 * Request an OTP for the given phone number.
 * Dev bypass numbers (+1555*) always generate OTP "000000".
 */
const requestOtp = async (baseUrl, phone) => {
  const resp = await axios.post(`${baseUrl}/auth/request-otp`, {
    channel: 'sms',
    value: phone,
  });
  if (resp.status !== 200) {
    throw new Error(`request-otp failed with status ${resp.status}`);
  }
  return resp;
};

/**
 * Verify an OTP and return the full auth response.
 * Stores accessToken, refreshToken, and userId in Bruno variables.
 */
const verifyOtp = async (baseUrl, phone, otp) => {
  const resp = await axios.post(`${baseUrl}/auth/verify-otp`, {
    channel: 'sms',
    value: phone,
    otp: otp,
  });
  if (resp.status !== 200) {
    throw new Error(`verify-otp failed with status ${resp.status}`);
  }
  return resp;
};

/**
 * Full login flow: request OTP then verify it.
 * Stores accessToken and userId in Bruno collection variables.
 *
 * @param {string} [phone] - Phone number to authenticate. Defaults to devPhone env var.
 * @returns {object} - The auth response data { accessToken, refreshToken, user }
 */
const login = async (phone) => {
  const baseUrl = bru.getEnvVar('baseUrl');
  const devPhone = phone || bru.getEnvVar('devPhone');
  const devOtp = bru.getEnvVar('devOtp');

  // Check if we already have a valid token
  const existingToken = bru.getVar('accessToken');
  if (existingToken) {
    try {
      const meResp = await axios.get(`${baseUrl}/users/me`, {
        headers: { Authorization: `Bearer ${existingToken}` },
      });
      if (meResp.status === 200) {
        console.log(`Already authenticated as user ${meResp.data.id}`);
        return { accessToken: existingToken, user: meResp.data };
      }
    } catch (e) {
      // Token expired or invalid, proceed with login
      console.log('Existing token invalid, re-authenticating...');
    }
  }

  await requestOtp(baseUrl, devPhone);
  const resp = await verifyOtp(baseUrl, devPhone, devOtp);

  bru.setVar('accessToken', resp.data.accessToken);
  bru.setVar('refreshToken', resp.data.refreshToken);
  bru.setVar('userId', resp.data.user.id);
  console.log(`Authenticated as user ${resp.data.user.id} (${devPhone})`);

  return resp.data;
};

module.exports = { requestOtp, verifyOtp, login };
