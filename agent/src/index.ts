import { SQSClient } from '@aws-sdk/client-sqs';
import { config } from './config';
import { close as closeDb } from './db';
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

let running = true;

/**
 * Main polling loop — calls pollOnce repeatedly until shutdown signal received.
 *
 * On SQS polling errors (network issues, service outage), pauses 5 seconds
 * before retrying to avoid tight error loops.
 */
async function pollLoop(sqsClient: SQSClient): Promise<void> {
  console.log('FieldIQ Agent starting — polling SQS for tasks...');

  while (running) {
    try {
      await pollOnce(
        sqsClient,
        config.aws.sqs.agentTasksQueue,
        config.worker.waitTimeSeconds,
        config.worker.maxMessages,
      );
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

// Start the agent (only when run directly, not when imported for testing)
if (require.main === module) {
  const sqsClient = new SQSClient({
    region: config.aws.region,
    endpoint: config.aws.endpointUrl,
  });

  pollLoop(sqsClient)
    .then(() => closeDb())
    .then(() => console.log('Agent stopped.'))
    .catch((error) => {
      console.error('Fatal error:', error);
      process.exit(1);
    });
}
