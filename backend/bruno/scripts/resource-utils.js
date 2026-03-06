/**
 * Generic resource creation and retrieval utilities for FieldIQ Bruno tests.
 *
 * All requests use Bearer token auth (stored in bru var 'accessToken').
 * Resource builders use Faker.js for realistic test data generation.
 */

const axios = require('axios');
const { faker } = require('@faker-js/faker');
const { login } = require('./auth-helpers.js');

// ----- HTTP helpers -----

/**
 * Make an authenticated GET request.
 */
const getResource = async (resourcePath) => {
  const baseUrl = bru.getEnvVar('baseUrl');
  const token = bru.getVar('accessToken');
  if (!token) throw new Error('No accessToken set. Run loginAs() first.');

  console.log('GET', resourcePath);
  const resp = await axios.get(`${baseUrl}/${resourcePath}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return resp;
};

/**
 * Make an authenticated POST request.
 */
const postResource = async (resourcePath, data) => {
  const baseUrl = bru.getEnvVar('baseUrl');
  const token = bru.getVar('accessToken');
  if (!token) throw new Error('No accessToken set. Run loginAs() first.');

  console.log('POST', resourcePath);
  const resp = await axios.post(`${baseUrl}/${resourcePath}`, data, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  return resp;
};

/**
 * Make an authenticated PATCH request.
 */
const patchResource = async (resourcePath, data) => {
  const baseUrl = bru.getEnvVar('baseUrl');
  const token = bru.getVar('accessToken');
  if (!token) throw new Error('No accessToken set. Run loginAs() first.');

  console.log('PATCH', resourcePath);
  const resp = await axios.patch(`${baseUrl}/${resourcePath}`, data, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  return resp;
};

/**
 * Make an authenticated DELETE request.
 */
const deleteResource = async (resourcePath) => {
  const baseUrl = bru.getEnvVar('baseUrl');
  const token = bru.getVar('accessToken');
  if (!token) throw new Error('No accessToken set. Run loginAs() first.');

  console.log('DELETE', resourcePath);
  const resp = await axios.delete(`${baseUrl}/${resourcePath}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return resp;
};

// ----- Data builders -----

const getCreateTeamBody = (overrides = {}) => {
  return {
    name: `${faker.location.city()} ${faker.animal.type()} U${faker.helpers.arrayElement([8, 10, 12, 14])}`,
    sport: 'soccer',
    ageGroup: `U${faker.helpers.arrayElement([8, 10, 12, 14])}`,
    season: `${faker.helpers.arrayElement(['Spring', 'Fall'])}${new Date().getFullYear()}`,
    ...overrides,
  };
};

const getCreateEventBody = (overrides = {}) => {
  const startsAt = faker.date.soon({ days: 14 }).toISOString();
  const endsAt = new Date(new Date(startsAt).getTime() + 90 * 60 * 1000).toISOString(); // +90 min
  return {
    eventType: faker.helpers.arrayElement(['game', 'practice']),
    title: `vs ${faker.location.city()} ${faker.animal.type()}`,
    location: `${faker.location.city()} Sports Complex`,
    locationNotes: `Park in Lot ${faker.string.alpha({ length: 1, casing: 'upper' })}`,
    startsAt,
    endsAt,
    ...overrides,
  };
};

const getCreateAvailabilityBody = (teamId, overrides = {}) => {
  return {
    teamId,
    dayOfWeek: faker.helpers.arrayElement([0, 1, 2, 3, 4, 5, 6]),
    startTime: faker.helpers.arrayElement(['09:00', '10:00', '14:00', '16:00']),
    endTime: faker.helpers.arrayElement(['11:00', '12:00', '16:00', '18:00']),
    windowType: 'available',
    ...overrides,
  };
};

module.exports = {
  getResource,
  postResource,
  patchResource,
  deleteResource,
  getCreateTeamBody,
  getCreateEventBody,
  getCreateAvailabilityBody,
};
