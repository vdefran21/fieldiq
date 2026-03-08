package com.fieldiq.websocket

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID

/**
 * WebSocket handler for phase-1 negotiation subscriptions.
 *
 * Connections are server-push only, so inbound text messages are ignored.
 *
 * @property sessionHub In-memory subscription registry used for fan-out.
 */
@Component
class NegotiationWebSocketHandler(
    private val sessionHub: NegotiationWebSocketSessionHub,
) : TextWebSocketHandler() {

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val negotiationId = session.attributes["negotiationId"] as? UUID ?: return
        sessionHub.register(negotiationId, session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) = Unit

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        sessionHub.unregister(session)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        sessionHub.unregister(session)
    }
}
