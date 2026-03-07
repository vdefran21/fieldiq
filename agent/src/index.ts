import {
  SQSClient,
  ReceiveMessageCommand,
  DeleteMessageCommand,
} from '@aws-sdk/client-sqs';
import { config } from './config';
import { close as closeDb } from './db';
import {
  handleSyncCalendar,
  SyncCalendarTask,
} from './workers/calendar-sync.worker';

/**
 * Agent layer entry point — polls SQS for tasks and dispatches to workers.
 *
 * Runs as a continuous loop, long-polling the agent tasks queue for messages.
 * Each message contains a task type and payload. The dispatcher routes tasks
 * to the appropriate worker function.
 *
 * **Task types:**
 * - `SYNC_CALENDAR` — syncs Google Calendar busy blocks (see calendar-sync.worker.ts)
 * - `SEND_NOTIFICATION` — dispatches push/SMS/email (Sprint 6)
 * - `SEND_REMINDERS` — drafts and sends event reminders via Claude Haiku (Sprint 6)
 *
 * **Graceful shutdown:** Listens for SIGINT/SIGTERM and stops polling after the
 * current batch completes. Closes the database connection pool on exit.
 */

const sqsClient = new SQSClient({
  region: config.aws.region,
  endpoint: config.aws.endpointUrl,
});

let running = true;

/**
 * Dispatches an SQS message to the appropriate worker based on taskType.
 *
 * @param body The parsed message body containing taskType and payload.
 */
async function dispatch(body: { taskType: string; [key: string]: unknown }): Promise<void> {
  switch (body.taskType) {
    case 'SYNC_CALENDAR':
      await handleSyncCalendar(body as unknown as SyncCalendarTask);
      break;

    // Sprint 6: SEND_NOTIFICATION, SEND_REMINDERS
    default:
      console.warn(`Unknown task type: ${body.taskType}`);
  }
}

/**
 * Main polling loop — receives and processes SQS messages.
 */
async function pollLoop(): Promise<void> {
  console.log('FieldIQ Agent starting — polling SQS for tasks...');

  while (running) {
    try {
      const response = await sqsClient.send(
        new ReceiveMessageCommand({
          QueueUrl: config.aws.sqs.agentTasksQueue,
          MaxNumberOfMessages: config.worker.maxMessages,
          WaitTimeSeconds: config.worker.waitTimeSeconds,
        }),
      );

      const messages = response.Messages || [];

      for (const message of messages) {
        if (!message.Body || !message.ReceiptHandle) continue;

        try {
          const body = JSON.parse(message.Body);
          console.log(`Processing task: ${body.taskType}`);
          await dispatch(body);

          // Delete message on success
          await sqsClient.send(
            new DeleteMessageCommand({
              QueueUrl: config.aws.sqs.agentTasksQueue,
              ReceiptHandle: message.ReceiptHandle,
            }),
          );
        } catch (error) {
          console.error('Task processing failed:', error);
          // Message will become visible again after visibility timeout for retry
        }
      }
    } catch (error) {
      console.error('SQS polling error:', error);
      // Brief pause before retrying to avoid tight error loops
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('Received SIGINT — shutting down gracefully...');
  running = false;
});

process.on('SIGTERM', () => {
  console.log('Received SIGTERM — shutting down gracefully...');
  running = false;
});

// Start the agent
pollLoop()
  .then(() => closeDb())
  .then(() => console.log('Agent stopped.'))
  .catch((error) => {
    console.error('Fatal error:', error);
    process.exit(1);
  });
