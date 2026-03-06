/**
 * Environment initialization for Bruno tests.
 *
 * Reads values from the active Bruno environment (instance-a.bru or instance-b.bru)
 * and makes them available via bru.getVar() for use in helper scripts.
 *
 * Bruno environment vars use camelCase (baseUrl, devPhone, devOtp).
 * This module is the single place that bridges env vars → collection vars.
 */

const initEnv = () => {
  bru.setVar('baseUrl', bru.getEnvVar('baseUrl'));
  bru.setVar('devPhone', bru.getEnvVar('devPhone'));
  bru.setVar('devOtp', bru.getEnvVar('devOtp'));
};

module.exports = { initEnv };
