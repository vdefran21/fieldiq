package com.fieldiq.websocket

import com.fieldiq.api.dto.MatchFoundMessage
import com.fieldiq.api.dto.NegotiationUpdateMessage
import com.fieldiq.api.dto.SessionConfirmedMessage
import com.fieldiq.api.dto.SessionFailedMessage
import com.fieldiq.api.dto.TimeSlotDto
import com.fieldiq.domain.NegotiationSession
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Converts negotiation domain state changes into outbound WebSocket messages.
 *
 * @property sessionHub Subscription registry used for actual message delivery.
 */
@Service
class NegotiationRealtimePublisher(
    private val sessionHub: NegotiationWebSocketSessionHub,
) {

    /**
     * Publishes the latest negotiation state and any derived milestone messages.
     *
     * @param session Current negotiation session snapshot.
     * @param lastEvent Short event key describing the triggering transition.
     */
    fun publishUpdate(session: NegotiationSession, lastEvent: String) {
        sessionHub.broadcast(
            session.id,
            NegotiationUpdateMessage(
                type = "negotiation_update",
                sessionId = session.id.toString(),
                status = session.status,
                currentRound = session.currentRound,
                lastEvent = lastEvent,
                timestamp = Instant.now().toString(),
            ),
        )

        when (session.status) {
            "pending_approval" -> {
                val startsAt = session.agreedStartsAt
                val endsAt = session.agreedEndsAt
                if (startsAt != null && endsAt != null) {
                    sessionHub.broadcast(
                        session.id,
                        MatchFoundMessage(
                            type = "match_found",
                            sessionId = session.id.toString(),
                            proposedSlot = TimeSlotDto(
                                startsAt = startsAt.toString(),
                                endsAt = endsAt.toString(),
                                location = session.agreedLocation,
                            ),
                            awaitingConfirmation = true,
                        ),
                    )
                }
            }
            "confirmed" -> {
                sessionHub.broadcast(
                    session.id,
                    SessionConfirmedMessage(
                        type = "session_confirmed",
                        sessionId = session.id.toString(),
                        eventId = session.id.toString(),
                        agreedStartsAt = session.agreedStartsAt?.toString() ?: Instant.now().toString(),
                        agreedLocation = session.agreedLocation,
                    ),
                )
            }
            "failed", "cancelled" -> {
                sessionHub.broadcast(
                    session.id,
                    SessionFailedMessage(
                        type = "session_failed",
                        sessionId = session.id.toString(),
                        reason = if (session.status == "cancelled") "cancelled" else "max_rounds_exceeded",
                    ),
                )
            }
        }
    }
}
