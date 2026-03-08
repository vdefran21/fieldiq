import {
  SQSClient,
  ReceiveMessageCommand,
  DeleteMessageCommand,
  Message,
} from '@aws-sdk/client-sqs';
import {
  handleSyncCalendar,
  SyncCalendarTask,
} from './workers/calendar-sync.worker';
import {
  handleSendNotification,
  NotificationTask,
} from './workers/notification.worker';

/**
 * Result of processing a single SQS message.
 *
 * Returned by {@link processMessage} so callers can inspect outcomes
 * without relying on side effects or exceptions.
 *
 * - `processed`: task dispatched and completed successfully, message deleted
 * - `failed`: task threw an error; check `deleted` to know if the message
 *   was removed (malformed JSON) or left for retry (worker error)
 */
export interface ProcessMessageResult {
  status: 'processed' | 'failed';
  /** Whether the message was deleted from SQS (true for success AND malformed JSON) */
  deleted: boolean;
  taskType?: string;
  error?: string;
}

/**
 * Aggregate result of a single SQS polling batch.
 *
 * Returned by {@link pollOnce} for structured assertions in tests
 * and operational observability.
 */
export interface PollResult {
  /** Number of messages received from the queue */
  received: number;
  /** Number of messages successfully dispatched to a worker */
  processed: number;
  /** Number of messages deleted from the queue (includes processed + deleted-on-parse-error) */
  deleted: number;
  /** Number of messages that failed and were left for retry */
  failed: number;
}

/**
 * Routes a parsed SQS message body to the appropriate worker function.
 *
 * This is the routing layer only — it maps `taskType` strings to worker
 * functions. It does not handle message lifecycle (parsing, deletion, retries).
 *
 * **Supported task types:**
 * - `SYNC_CALENDAR` → {@link handleSyncCalendar}
 * - `SEND_NOTIFICATION` → {@link handleSendNotification}
 * - `SEND_REMINDERS` → Sprint 6
 *
 * Unknown task types log a warning but do not throw, so the message
 * will still be deleted by {@link processMessage} (treated as non-fatal).
 *
 * @param body The parsed message body containing `taskType` and task-specific fields.
 */
export async function dispatchTask(
  body: { taskType: string; [key: string]: unknown },
): Promise<void> {
  switch (body.taskType) {
    case 'SYNC_CALENDAR':
      await handleSyncCalendar(body as unknown as SyncCalendarTask);
      break;

    case 'SEND_NOTIFICATION':
      await handleSendNotification(body as unknown as NotificationTask);
      break;

    // Sprint 6: SEND_NOTIFICATION, SEND_REMINDERS
    default:
      console.warn(`Unknown task type: ${body.taskType}`);
  }
}

/**
 * Processes a single SQS message: parse, dispatch, delete on success.
 *
 * Owns message-level semantics:
 * 1. Parse the message body as JSON
 * 2. Call {@link dispatchTask} to route to the appropriate worker
 * 3. On success: delete the message from SQS and return `{ status: 'processed' }`
 * 4. On failure: log the error, leave the message in the queue for retry,
 *    and return `{ status: 'failed' }`
 *
 * Does NOT rethrow worker errors. A failed message stays in the queue
 * and becomes visible again after the SQS visibility timeout.
 *
 * @param message The raw SQS message (must have Body and ReceiptHandle).
 * @param sqsClient The SQS client for delete operations.
 * @param queueUrl The queue URL for delete operations.
 * @returns Structured result indicating success or failure.
 */
export async function processMessage(
  message: Message,
  sqsClient: SQSClient,
  queueUrl: string,
): Promise<ProcessMessageResult> {
  if (!message.Body || !message.ReceiptHandle) {
    return { status: 'failed', deleted: false, error: 'Message missing Body or ReceiptHandle' };
  }

  let body: { taskType: string; [key: string]: unknown };
  try {
    body = JSON.parse(message.Body);
  } catch {
    console.error('Failed to parse SQS message body:', message.Body);
    // Delete malformed messages — retrying won't help
    await sqsClient.send(
      new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle }),
    );
    return { status: 'failed', deleted: true, error: 'Invalid JSON in message body' };
  }

  try {
    console.log(`Processing task: ${body.taskType}`);
    await dispatchTask(body);

    // Delete message on success
    await sqsClient.send(
      new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle }),
    );

    return { status: 'processed', deleted: true, taskType: body.taskType };
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error(`Task processing failed (${body.taskType}):`, errorMessage);
    // Message stays in queue — will become visible again after visibility timeout
    return { status: 'failed', deleted: false, taskType: body.taskType, error: errorMessage };
  }
}

/**
 * Executes one SQS polling batch: receive messages and process each one.
 *
 * This is the batch orchestration layer. It issues a single
 * `ReceiveMessageCommand`, then calls {@link processMessage} for each
 * message in the batch. Returns structured results so callers (the
 * bootstrap loop or integration tests) can observe outcomes.
 *
 * @param sqsClient The SQS client for receive and delete operations.
 * @param queueUrl The queue URL to poll.
 * @param waitTimeSeconds SQS long-poll wait time (default 20).
 * @param maxMessages Maximum messages per batch (default 10).
 * @returns Aggregate counts of received, processed, deleted, and failed messages.
 */
export async function pollOnce(
  sqsClient: SQSClient,
  queueUrl: string,
  waitTimeSeconds: number = 20,
  maxMessages: number = 10,
): Promise<PollResult> {
  const response = await sqsClient.send(
    new ReceiveMessageCommand({
      QueueUrl: queueUrl,
      MaxNumberOfMessages: maxMessages,
      WaitTimeSeconds: waitTimeSeconds,
    }),
  );

  const messages = response.Messages || [];
  const result: PollResult = {
    received: messages.length,
    processed: 0,
    deleted: 0,
    failed: 0,
  };

  for (const message of messages) {
    const messageResult = await processMessage(message, sqsClient, queueUrl);
    if (messageResult.status === 'processed') {
      result.processed++;
    } else {
      result.failed++;
    }
    if (messageResult.deleted) {
      result.deleted++;
    }
  }

  return result;
}
