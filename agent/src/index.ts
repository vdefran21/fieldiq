import { SQSClient } from '@aws-sdk/client-sqs';
import { config } from './config';
import { close as closeDb } from './db';
import {
  createSqsClient,
  getAgentTasksQueueUrl,
  getNotificationsQueueUrl,
} from './sqs-client';
import { pollOnce } from './task-dispatcher';

/**
 * Agent layer entry point — thin bootstrap that starts the SQS polling loop.
 *
 * All runtime logic (message routing, processing, deletion) lives in
 * {@link task-dispatcher.ts}. This file is responsible only for:
 * - Creating the SQS client from config
 * - Running the polling loop
 * - Handling graceful shutdown signals
 *
 * Guarded by `require.main === module` so importing this file in tests
 * does not trigger the polling loop.
 *
 * @see task-dispatcher.ts for dispatchTask, processMessage, and pollOnce
 */

/**
 * Process-wide polling flag toggled by shutdown signals.
 *
 * The agent is single-process in Phase 1, so a simple in-memory flag is
 * sufficient to let the current batch finish before the loop exits.
 */
let running = true;

/**
 * Main polling loop — calls pollOnce repeatedly until shutdown signal received.
 *
 * On SQS polling errors (network issues, service outage), pauses 5 seconds
 * before retrying to avoid tight error loops.
 */
async function pollLoop(sqsClient: SQSClient): Promise<void> {
  console.log('FieldIQ Agent starting — polling SQS for agent and notification tasks...');

  while (running) {
    try {
      await pollOnce(sqsClient, getAgentTasksQueueUrl(), config.worker.waitTimeSeconds, config.worker.maxMessages);
      await pollOnce(sqsClient, getNotificationsQueueUrl(), 1, config.worker.maxMessages);
    } catch (error) {
      console.error('SQS polling error:', error);
      // Brief pause before retrying to avoid tight error loops
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}

/**
 * Registers a signal handler that stops the polling loop gracefully.
 *
 * The handler does not force-close in-flight work. It only flips the shared
 * `running` flag so the current SQS batch can complete before shutdown.
 *
 * @param signal POSIX signal that should trigger agent shutdown.
 */
function registerShutdownHandler(signal: NodeJS.Signals): void {
  process.on(signal, () => {
    console.log(`Received ${signal} — shutting down gracefully...`);
    running = false;
  });
}

registerShutdownHandler('SIGINT');
registerShutdownHandler('SIGTERM');

// Start the agent (only when run directly, not when imported for testing)
if (require.main === module) {
  const sqsClient = createSqsClient();

  pollLoop(sqsClient)
    .then(() => {
      sqsClient.destroy();
      return closeDb();
    })
    .then(() => console.log('Agent stopped.'))
    .catch((error) => {
      sqsClient.destroy();
      console.error('Fatal error:', error);
      process.exit(1);
    });
}
