package com.fieldiq.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains in-memory WebSocket subscriptions per negotiation session.
 *
 * Phase 1 only needs ephemeral server-push delivery for the currently connected mobile
 * clients, so a single-process in-memory registry is sufficient.
 *
 * @property objectMapper JSON serializer for outbound messages.
 */
@Component
class NegotiationWebSocketSessionHub(
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(NegotiationWebSocketSessionHub::class.java)
    private val subscriptions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    /**
     * Registers a WebSocket session for a negotiation subscription.
     *
     * @param negotiationId Negotiation session UUID from the request path.
     * @param session Accepted WebSocket session.
     */
    fun register(negotiationId: UUID, session: WebSocketSession) {
        subscriptions.computeIfAbsent(negotiationId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    /**
     * Unregisters a WebSocket session after disconnect or transport error.
     *
     * @param session WebSocket session to remove.
     */
    fun unregister(session: WebSocketSession) {
        subscriptions.values.forEach { it.remove(session) }
    }

    /**
     * Broadcasts a serializable payload to all current subscribers.
     *
     * @param negotiationId Negotiation whose subscribers should receive the message.
     * @param payload Shared-contract DTO serialized as JSON.
     */
    fun broadcast(negotiationId: UUID, payload: Any) {
        val sessions = subscriptions[negotiationId] ?: return
        val message = TextMessage(objectMapper.writeValueAsString(payload))
        val staleSessions = mutableListOf<WebSocketSession>()

        sessions.forEach { session ->
            if (session.isOpen) {
                session.sendMessage(message)
            } else {
                staleSessions += session
            }
        }

        staleSessions.forEach { sessions.remove(it) }
        logger.debug("Broadcasted websocket payload to {} subscribers for {}", sessions.size, negotiationId)
    }
}
