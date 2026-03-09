import dotenv from 'dotenv';
dotenv.config();

/**
 * AWS credential pair used by the agent when talking to SQS.
 *
 * LocalStack still requires signed requests, so integration tests and local
 * development must provide credentials even though no real AWS account is used.
 */
export interface AwsCredentialsConfig {
  /** Access key used to sign SQS requests. */
  accessKeyId: string;
  /** Secret key paired with the access key for request signing. */
  secretAccessKey: string;
}

/**
 * Queue URLs consumed by the agent runtime.
 *
 * Keeping queue names in config rather than inline strings prevents drift
 * between runtime polling code, integration tests, and infrastructure setup.
 */
export interface AwsQueueConfig {
  /** Queue carrying backend-originated agent tasks such as calendar sync work. */
  agentTasksQueue: string;
  /** Queue carrying device notification delivery work for Expo push. */
  notificationsQueue: string;
}

/**
 * AWS and LocalStack wiring for the agent runtime.
 */
export interface AwsConfig {
  /** AWS region used for request signing and client construction. */
  region: string;
  /** Base endpoint for LocalStack in local development or AWS in deployed environments. */
  endpointUrl: string;
  /** Static credentials used to sign SQS requests. */
  credentials: AwsCredentialsConfig;
  /** Queue URLs consumed by the agent runtime. */
  sqs: AwsQueueConfig;
}

/**
 * Shared PostgreSQL connectivity used by workers and integration tests.
 */
export interface DatabaseConfig {
  /** Connection string for the same database schema used by the Kotlin backend. */
  connectionString: string;
}

/**
 * Google OAuth client settings used when refreshing access tokens.
 */
export interface GoogleConfig {
  /** Google OAuth client ID matching the backend's calendar integration setup. */
  clientId: string;
  /** Google OAuth client secret paired with the configured client ID. */
  clientSecret: string;
}

/**
 * Expo push transport settings for notification delivery.
 */
export interface ExpoConfig {
  /** HTTPS endpoint that accepts Expo push payload batches. */
  pushEndpoint: string;
  /** Optional Expo access token for authenticated push delivery. */
  accessToken: string;
}

/**
 * Shared encryption configuration for calendar token decryption.
 */
export interface EncryptionConfig {
  /** Raw token-encryption secret that must match the backend's AES key material. */
  tokenKey: string;
}

/**
 * Worker polling and scheduling settings.
 */
export interface WorkerConfig {
  /** SQS long-poll wait time, in seconds, for the agent tasks queue. */
  waitTimeSeconds: number;
  /** Maximum number of SQS messages requested in a single polling batch. */
  maxMessages: number;
  /** Number of days ahead to request Google FreeBusy data for availability sync. */
  freeBusyLookAheadDays: number;
}

/**
 * Agent layer configuration, loaded from environment variables.
 *
 * All values have sensible defaults for local development (matching the
 * backend's docker-compose setup). In production, these are set via
 * environment variables or AWS Secrets Manager.
 */
export interface AgentConfig {
  /** AWS / LocalStack connectivity used by the SQS workers. */
  aws: AwsConfig;
  /** Shared Postgres connection settings. */
  database: DatabaseConfig;
  /** Google OAuth configuration used for token refresh. */
  google: GoogleConfig;
  /** Expo push transport configuration. */
  expo: ExpoConfig;
  /** Shared token-encryption settings. */
  encryption: EncryptionConfig;
  /** Polling and worker-execution configuration. */
  worker: WorkerConfig;
}

/**
 * Resolved agent configuration for the current process.
 *
 * The agent intentionally centralizes environment parsing here so worker code
 * can depend on typed config objects instead of reaching into `process.env`.
 * This keeps integration tests, runtime bootstrap, and worker logic aligned
 * on the same defaults and failure surface.
 */
export const config: AgentConfig = {
  /** AWS / LocalStack configuration. */
  aws: {
    region: process.env.AWS_REGION || 'us-east-1',
    endpointUrl: process.env.AWS_ENDPOINT_URL || 'http://localhost:4566',
    credentials: {
      accessKeyId: process.env.AWS_ACCESS_KEY_ID || 'test',
      secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY || 'test',
    },
    sqs: {
      agentTasksQueue:
        process.env.AGENT_TASKS_QUEUE_URL ||
        'http://localhost:4566/000000000000/fieldiq-agent-tasks',
      notificationsQueue:
        process.env.NOTIFICATIONS_QUEUE_URL ||
        'http://localhost:4566/000000000000/fieldiq-notifications',
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

  /** Expo push transport configuration. */
  expo: {
    pushEndpoint:
      process.env.EXPO_PUSH_ENDPOINT ||
      'https://exp.host/--/api/v2/push/send',
    accessToken: process.env.EXPO_ACCESS_TOKEN || '',
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
