package com.fieldiq.service

/**
 * Thrown when a negotiation state machine transition is invalid.
 *
 * The negotiation protocol enforces a strict state machine. Attempts to perform
 * actions that are not allowed in the current state (e.g., confirming a session
 * that is still in "proposing" status, or transitioning a terminal session) throw
 * this exception. Mapped to HTTP 409 Conflict by [com.fieldiq.api.GlobalExceptionHandler].
 *
 * **Allowed transitions:**
 * ```
 * pending_response → proposing, cancelled, failed
 * proposing        → pending_approval, failed, cancelled
 * pending_approval → confirmed, proposing, cancelled
 * confirmed, failed, cancelled → (terminal, no transitions)
 * ```
 *
 * @property message Human-readable description of the invalid transition attempt.
 * @see com.fieldiq.service.NegotiationService for the state machine enforcement.
 * @see com.fieldiq.api.GlobalExceptionHandler for the HTTP response mapping.
 */
class InvalidStateTransitionException(message: String) : RuntimeException(message)
