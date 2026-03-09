import { SQSClient, SQSClientConfig } from '@aws-sdk/client-sqs';
import { config } from './config';

/**
 * Builds the shared SQS client configuration for runtime and integration code.
 *
 * LocalStack still expects signed SQS requests, so the agent uses explicit
 * credentials even in local development rather than relying on the AWS SDK's
 * environment and metadata provider chain.
 *
 * @returns SQS client options aligned with the current agent environment.
 */
export function buildSqsClientConfig(): SQSClientConfig {
  return {
    region: config.aws.region,
    endpoint: config.aws.endpointUrl,
    credentials: config.aws.credentials,
  };
}

/**
 * Creates an SQS client using the shared FieldIQ configuration.
 *
 * This helper keeps runtime bootstrap code and integration-test setup on the
 * same wiring path so credential or endpoint changes cannot drift between them.
 *
 * @returns A configured SQS client for LocalStack or AWS.
 */
export function createSqsClient(): SQSClient {
  return new SQSClient(buildSqsClientConfig());
}

/**
 * Returns the configured agent tasks queue URL.
 *
 * Keeping queue lookup here ensures runtime bootstrap and integration tests use
 * the same queue wiring path as the shared SQS client construction.
 *
 * @returns Queue URL for agent task messages.
 */
export function getAgentTasksQueueUrl(): string {
  return config.aws.sqs.agentTasksQueue;
}

/**
 * Returns the configured notifications queue URL.
 *
 * Notification delivery tasks are published separately from calendar sync so the
 * agent can drain both streams without coupling their throughput.
 *
 * @returns Queue URL for notification messages.
 */
export function getNotificationsQueueUrl(): string {
  return config.aws.sqs.notificationsQueue;
}
