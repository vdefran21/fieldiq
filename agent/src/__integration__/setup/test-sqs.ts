import {
    DeleteMessageCommand,
    GetQueueAttributesCommand,
    PurgeQueueCommand,
    ReceiveMessageCommand,
    SQSClient,
    SendMessageCommand,
} from '@aws-sdk/client-sqs';
import { createSqsClient, getAgentTasksQueueUrl } from '../../sqs-client';

/**
 * SQS test helpers for integration tests.
 *
 * Uses queue URL from config (not hardcoded) so tests stay aligned
 * with the agent's runtime configuration. These helpers intentionally use the
 * same client-construction path as production code to catch endpoint or queue
 * drift before it reaches runtime.
 */

/**
 * Shared LocalStack SQS client reused across integration tests in this process.
 */
const sqsClient = createSqsClient();

/**
 * Agent task queue URL used by integration tests.
 */
const queueUrl = getAgentTasksQueueUrl();

/**
 * Returns the shared SQS client for use in tests that call pollOnce() directly.
 */
export function getSqsClient(): SQSClient {
  return sqsClient;
}

/**
 * Returns the queue URL from config.
 *
 * Tests pass this directly into `pollOnce()` so assertions exercise the same
 * queue URL resolution path as the runtime bootstrap.
 */
export function getQueueUrl(): string {
  return queueUrl;
}

/**
 * Destroys the shared integration-test SQS client.
 *
 * Jest keeps Node HTTP handlers alive while the client exists, so teardown must
 * explicitly destroy it to prevent leaked-handle warnings.
 */
export function closeTestSqsClient(): void {
  sqsClient.destroy();
}

/**
 * Enqueues a task message to the agent tasks queue.
 *
 * @param payload The task body (will be JSON-stringified).
 * @returns The SQS MessageId.
 */
export async function sendTask(payload: Record<string, unknown>): Promise<string> {
  const result = await sqsClient.send(
    new SendMessageCommand({
      QueueUrl: queueUrl,
      MessageBody: JSON.stringify(payload),
    }),
  );
  return result.MessageId!;
}

/**
 * Purges all messages from the agent tasks queue.
 *
 * Falls back to manually draining if PurgeQueue fails (SQS enforces
 * a 60-second cooldown between purge calls).
 */
export async function purgeQueue(): Promise<void> {
  try {
    await sqsClient.send(new PurgeQueueCommand({ QueueUrl: queueUrl }));
  } catch {
    // PurgeQueue 60s cooldown — drain manually
    await drainQueue();
  }
}

/**
 * Returns the approximate number of messages in the queue.
 *
 * This counts both visible and in-flight messages so retry-behavior assertions
 * can detect messages that were intentionally left undeleted after worker failure.
 */
export async function getQueueMessageCount(): Promise<number> {
  const result = await sqsClient.send(
    new GetQueueAttributesCommand({
      QueueUrl: queueUrl,
      AttributeNames: ['ApproximateNumberOfMessages', 'ApproximateNumberOfMessagesNotVisible'],
    }),
  );
  const visible = parseInt(result.Attributes?.ApproximateNumberOfMessages || '0', 10);
  const notVisible = parseInt(result.Attributes?.ApproximateNumberOfMessagesNotVisible || '0', 10);
  return visible + notVisible;
}

/**
 * Drains all visible messages from the queue by receiving and deleting each one.
 *
 * Unlike PurgeQueue, this has no cooldown. Each received message is
 * explicitly deleted so it cannot reappear after the visibility timeout.
 */
async function drainQueue(): Promise<void> {
  let hasMessages = true;
  while (hasMessages) {
    const response = await sqsClient.send(
      new ReceiveMessageCommand({
        QueueUrl: queueUrl,
        MaxNumberOfMessages: 10,
        WaitTimeSeconds: 1,
      }),
    );
    const messages = response.Messages || [];
    hasMessages = messages.length > 0;

    for (const message of messages) {
      if (message.ReceiptHandle) {
        await sqsClient.send(
          new DeleteMessageCommand({
            QueueUrl: queueUrl,
            ReceiptHandle: message.ReceiptHandle,
          }),
        );
      }
    }
  }
}
