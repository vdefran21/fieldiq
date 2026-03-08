package com.fieldiq.websocket

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Registers the phase-1 negotiation WebSocket endpoint.
 *
 * @property handler Raw WebSocket handler for negotiation subscriptions.
 * @property handshakeInterceptor Handshake-time JWT and membership validator.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: NegotiationWebSocketHandler,
    private val handshakeInterceptor: NegotiationWebSocketHandshakeInterceptor,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/negotiations/*")
            .addInterceptors(handshakeInterceptor)
            .setAllowedOriginPatterns("*")
    }
}
