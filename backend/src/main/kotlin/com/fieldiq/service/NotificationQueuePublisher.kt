package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.Event
import com.fieldiq.domain.NegotiationSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

/**
 * Publishes minimal notification tasks to the agent layer.
 *
 * This keeps notification delivery asynchronous and decoupled from the backend request
 * path. Phase 1 only requires milestone notifications for negotiation updates and
 * confirmed events; richer templates and reminder flows are deferred.
 *
 * @property sqsClient SQS client used to publish tasks.
 * @property properties FieldIQ configuration with queue URLs.
 * @property objectMapper JSON serializer for queue payloads.
 */
@Service
class NotificationQueuePublisher(
    private val sqsClient: SqsClient,
    private val properties: FieldIQProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(NotificationQueuePublisher::class.java)

    /**
     * Enqueues a negotiation lifecycle update for both teams.
     *
     * @param session Current negotiation session snapshot.
     * @param eventName Short event label describing what changed.
     */
    fun enqueueNegotiationUpdate(session: NegotiationSession, eventName: String) {
        publish(
            mapOf(
                "taskType" to "SEND_NOTIFICATION",
                "notificationType" to "negotiation_update",
                "sessionId" to session.id.toString(),
                "eventName" to eventName,
                "status" to session.status,
                "teamIds" to listOfNotNull(session.initiatorTeamId.toString(), session.responderTeamId?.toString()),
                "agreedStartsAt" to session.agreedStartsAt?.toString(),
                "agreedEndsAt" to session.agreedEndsAt?.toString(),
                "agreedLocation" to session.agreedLocation,
            ),
        )
    }

    /**
     * Enqueues a notification for a newly created event.
     *
     * @param event Scheduled event created locally.
     */
    fun enqueueEventCreated(event: Event) {
        publish(
            mapOf(
                "taskType" to "SEND_NOTIFICATION",
                "notificationType" to "event_created",
                "eventId" to event.id.toString(),
                "teamIds" to listOf(event.teamId.toString()),
                "startsAt" to event.startsAt?.toString(),
                "location" to event.location,
                "title" to event.title,
                "icsUrl" to "/events/${event.id}/ics",
            ),
        )
    }

    /**
     * Serializes and publishes a notification task if the queue is configured.
     *
     * @param payload Task payload to publish as JSON.
     */
    private fun publish(payload: Map<String, Any?>) {
        val queueUrl = properties.aws.sqs.notificationsQueue
        if (queueUrl.isBlank()) {
            logger.debug("Skipping notification publish because notifications queue URL is blank")
            return
        }

        sqsClient.sendMessage(
            SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(objectMapper.writeValueAsString(payload))
                .build(),
        )
        logger.info(
            "Queued notification task: notificationType={}, sessionId={}, eventId={}, teamCount={}",
            payload["notificationType"],
            payload["sessionId"],
            payload["eventId"],
            (payload["teamIds"] as? List<*>)?.size ?: 0,
        )
    }
}
