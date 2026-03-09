package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.config.FieldIQProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.util.UUID

/**
 * Publishes non-notification agent tasks to the shared SQS worker queue.
 *
 * Phase 1 uses this publisher for Google Calendar sync bootstrap after OAuth connect.
 * The payload contract is intentionally small and backend-owned so the agent can evolve
 * transport details without changing the core backend flow.
 *
 * @property sqsClient SQS client used for queue publishing.
 * @property properties FieldIQ configuration containing the agent task queue URL.
 * @property objectMapper JSON serializer for SQS payloads.
 */
@Service
class AgentTaskQueuePublisher(
    private val sqsClient: SqsClient,
    private val properties: FieldIQProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(AgentTaskQueuePublisher::class.java)

    /**
     * Enqueues one `SYNC_CALENDAR` task per active team membership for the user.
     *
     * The calendar sync worker needs a concrete `teamId` because busy blocks are stored as
     * availability windows per team. Users connected to multiple teams therefore emit one
     * queue message per team.
     *
     * @param userId User whose Google Calendar was connected or refreshed.
     * @param teamIds Team IDs that should receive calendar-derived availability windows.
     */
    fun enqueueCalendarSync(userId: UUID, teamIds: List<UUID>) {
        val queueUrl = properties.aws.sqs.agentTasksQueue
        if (queueUrl.isBlank()) {
            logger.debug("Skipping calendar sync publish because agent task queue URL is blank")
            return
        }

        teamIds.distinct().forEach { teamId ->
            val payload = mapOf(
                "taskType" to "SYNC_CALENDAR",
                "userId" to userId.toString(),
                "teamId" to teamId.toString(),
            )

            sqsClient.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(payload))
                    .build(),
            )
            logger.info("Queued calendar sync task: userId={}, teamId={}", userId, teamId)
        }
    }
}
