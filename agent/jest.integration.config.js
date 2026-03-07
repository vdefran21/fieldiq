/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__integration__/**/*.integration.test.ts'],
  testTimeout: 30000,
  globalSetup: '<rootDir>/src/__integration__/setup/global-setup.ts',
  globalTeardown: '<rootDir>/src/__integration__/setup/global-teardown.ts',
  collectCoverage: false,
};
