package com.fieldiq.api.dto

/**
 * Generic negotiation status update pushed via WebSocket.
 *
 * Corresponds to TypeScript interface: `NegotiationUpdateMessage` in `shared/types/index.ts`.
 */
data class NegotiationUpdateMessage(
    val type: String,
    val sessionId: String,
    val status: String,
    val currentRound: Int,
    val lastEvent: String,
    val timestamp: String,
)

/**
 * Match-found message pushed when the system finds overlapping availability.
 *
 * Corresponds to TypeScript interface: `MatchFoundMessage` in `shared/types/index.ts`.
 */
data class MatchFoundMessage(
    val type: String,
    val sessionId: String,
    val proposedSlot: TimeSlotDto,
    val awaitingConfirmation: Boolean,
)

/**
 * Confirmation message pushed after a negotiation reaches the confirmed state.
 *
 * Corresponds to TypeScript interface: `SessionConfirmedMessage` in `shared/types/index.ts`.
 */
data class SessionConfirmedMessage(
    val type: String,
    val sessionId: String,
    val eventId: String,
    val agreedStartsAt: String,
    val agreedLocation: String? = null,
)

/**
 * Terminal failure or cancellation message pushed to connected clients.
 *
 * Corresponds to TypeScript interface: `SessionFailedMessage` in `shared/types/index.ts`.
 */
data class SessionFailedMessage(
    val type: String,
    val sessionId: String,
    val reason: String,
)
