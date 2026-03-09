package com.fieldiq.websocket

import com.fieldiq.config.FieldIQProperties
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.repository.TeamMemberRepository
import com.fieldiq.security.JwtService
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.util.PatternMatchUtils
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.UUID

/**
 * Authenticates and authorizes negotiation WebSocket subscriptions.
 *
 * The mobile client first exchanges its bearer-authenticated REST session for a short-lived
 * negotiation-scoped token, then passes that `wsToken` in the handshake query string.
 * This interceptor validates the token, optionally checks the `Origin` header when present,
 * and ensures the user belongs to one of the teams referenced by the negotiation session
 * before the socket is accepted.
 *
 * @property properties Application configuration containing allowed origin patterns.
 * @property jwtService JWT validator for the handshake token.
 * @property sessionRepository Negotiation session lookup for authorization.
 * @property teamMemberRepository Team membership lookup for access control.
 */
@Component
class NegotiationWebSocketHandshakeInterceptor(
    private val properties: FieldIQProperties,
    private val jwtService: JwtService,
    private val sessionRepository: NegotiationSessionRepository,
    private val teamMemberRepository: TeamMemberRepository,
) : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val servletRequest = request as? ServletServerHttpRequest ?: return reject(response, HttpStatus.BAD_REQUEST)
        val negotiationId = extractNegotiationId(servletRequest.servletRequest.requestURI)
            ?: return reject(response, HttpStatus.BAD_REQUEST)
        val origin = servletRequest.servletRequest.getHeader("Origin")
        if (!isAllowedOrigin(origin)) {
            return reject(response, HttpStatus.FORBIDDEN)
        }

        val socketToken = servletRequest.servletRequest.getParameter("wsToken")
            ?: servletRequest.servletRequest.getParameter("token")
            ?: return reject(response, HttpStatus.UNAUTHORIZED)
        val userId = jwtService.validateNegotiationSocketToken(socketToken, negotiationId)
            ?: return reject(response, HttpStatus.UNAUTHORIZED)
        val session = sessionRepository.findById(negotiationId).orElse(null) ?: return reject(response, HttpStatus.NOT_FOUND)

        val authorized = listOfNotNull(session.initiatorTeamId, session.responderTeamId).any { teamId ->
            teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(userId, teamId) != null
        }
        if (!authorized) {
            return reject(response, HttpStatus.FORBIDDEN)
        }

        attributes["negotiationId"] = negotiationId
        attributes["userId"] = userId
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    /**
     * Parses `/ws/negotiations/{id}` into a UUID.
     *
     * @param path Raw request path.
     * @return Parsed negotiation UUID if valid.
     */
    private fun extractNegotiationId(path: String): UUID? =
        path.substringAfter("/ws/negotiations/", "").takeIf { it.isNotBlank() }?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

    /**
     * Validates the request origin when one is supplied by the client.
     *
     * Native mobile clients often omit `Origin`, so absence is treated as acceptable.
     * Browser-originated requests must match one of the configured patterns.
     *
     * @param origin Raw `Origin` header from the handshake request.
     * @return True when the origin is absent or matches an allowed pattern.
     */
    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) {
            return true
        }

        return properties.websocket.allowedOriginPatterns.any { pattern ->
            PatternMatchUtils.simpleMatch(pattern, origin)
        }
    }

    /**
     * Rejects the handshake with a specific HTTP status.
     *
     * @param response Handshake response to mutate.
     * @param status HTTP status to return.
     * @return Always `false` to stop the handshake.
     */
    private fun reject(response: ServerHttpResponse, status: HttpStatus): Boolean {
        response.setStatusCode(status)
        return false
    }
}
