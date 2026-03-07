import dotenv from 'dotenv';
dotenv.config();

/**
 * Agent layer configuration, loaded from environment variables.
 *
 * All values have sensible defaults for local development (matching the
 * backend's docker-compose setup). In production, these are set via
 * environment variables or AWS Secrets Manager.
 */
export const config = {
  /** AWS / LocalStack configuration. */
  aws: {
    region: process.env.AWS_REGION || 'us-east-1',
    endpointUrl: process.env.AWS_ENDPOINT_URL || 'http://localhost:4566',
    sqs: {
      agentTasksQueue:
        process.env.AGENT_TASKS_QUEUE_URL ||
        'http://localhost:4566/000000000000/fieldiq-agent-tasks',
    },
  },

  /** PostgreSQL connection — reads from the same DB as the backend. */
  database: {
    connectionString:
      process.env.DATABASE_URL ||
      'postgresql://fieldiq:localdev@localhost:5432/fieldiq',
  },

  /** Google API credentials (same as backend, for token refresh). */
  google: {
    clientId: process.env.GOOGLE_CLIENT_ID || '',
    clientSecret: process.env.GOOGLE_CLIENT_SECRET || '',
  },

  /** Token encryption key — must match the backend's key to decrypt tokens. */
  encryption: {
    tokenKey:
      process.env.TOKEN_ENCRYPTION_KEY ||
      'dev-token-encryption-key-change-in-production!32',
  },

  /** Worker polling configuration. */
  worker: {
    /** How long to wait for SQS messages (long polling, max 20s). */
    waitTimeSeconds: 20,
    /** Max messages per poll batch. */
    maxMessages: 10,
    /** How far ahead to query FreeBusy (in days). */
    freeBusyLookAheadDays: 30,
  },
};
