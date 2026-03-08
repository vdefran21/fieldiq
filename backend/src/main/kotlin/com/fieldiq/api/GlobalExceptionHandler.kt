package com.fieldiq.api

import com.fieldiq.api.dto.ErrorResponse
import com.fieldiq.service.GoogleOAuthException
import com.fieldiq.service.InvalidOtpException
import com.fieldiq.service.InvalidStateTransitionException
import com.fieldiq.service.RateLimitExceededException
import com.fieldiq.service.RelayException
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Global exception handler for all REST controllers.
 *
 * Provides consistent error response formatting across the entire API by catching
 * known exception types and mapping them to appropriate HTTP status codes with
 * structured [ErrorResponse] bodies.
 *
 * The mobile client expects all errors to follow the ErrorResponse format:
 * { error: string, message: string, status: number }.
 *
 * @see ErrorResponse for the standard error envelope.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Handles access denied errors from [com.fieldiq.service.TeamAccessGuard].
     *
     * @return 403 Forbidden with error details.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("FORBIDDEN", ex.message ?: "Access denied", 403))
    }

    /**
     * Handles OTP and refresh token validation failures.
     *
     * @return 401 Unauthorized with error details.
     */
    @ExceptionHandler(InvalidOtpException::class)
    fun handleInvalidOtp(ex: InvalidOtpException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("UNAUTHORIZED", ex.message ?: "Authentication failed", 401))
    }

    /**
     * Handles OTP rate limit violations.
     *
     * @return 429 Too Many Requests with error details.
     */
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimit(ex: RateLimitExceededException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse("RATE_LIMITED", ex.message ?: "Rate limit exceeded", 429))
    }

    /**
     * Handles entity-not-found errors (e.g., team, event, user lookup failures).
     *
     * @return 404 Not Found with error details.
     */
    @ExceptionHandler(EntityNotFoundException::class)
    fun handleNotFound(ex: EntityNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("NOT_FOUND", ex.message ?: "Resource not found", 404))
    }

    /**
     * Handles invalid arguments (format validation, business rule violations).
     *
     * @return 400 Bad Request with error details.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("BAD_REQUEST", ex.message ?: "Invalid request", 400))
    }

    /**
     * Handles Jakarta Bean Validation failures from @Valid annotated parameters.
     *
     * Extracts all field-level validation errors and returns them as a comma-separated
     * message string.
     *
     * @return 400 Bad Request with validation error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("VALIDATION_ERROR", errors, 400))
    }

    /**
     * Handles path and query parameter type conversion failures.
     *
     * This most commonly occurs when a route expects a UUID but receives an
     * invalid string. Returning 400 keeps malformed client requests out of the
     * generic 500 handler and makes Bruno failures easier to diagnose.
     *
     * @return 400 Bad Request with parameter conversion details.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        val parameterName = ex.name
        val requiredType = ex.requiredType?.simpleName ?: "required type"
        val value = ex.value?.toString() ?: "null"
        val message = "Parameter '$parameterName' must be a valid $requiredType: '$value'"

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("BAD_REQUEST", message, 400))
    }

    /**
     * Handles Google OAuth flow failures.
     *
     * @return 502 Bad Gateway with error details (the upstream Google API failed).
     */
    @ExceptionHandler(GoogleOAuthException::class)
    fun handleGoogleOAuth(ex: GoogleOAuthException): ResponseEntity<ErrorResponse> {
        logger.error("Google OAuth error: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("GOOGLE_OAUTH_ERROR", ex.message ?: "Google OAuth failed", 502))
    }

    /**
     * Handles invalid negotiation state machine transitions.
     *
     * Thrown when an action is attempted on a negotiation session in a state
     * that does not allow that action (e.g., confirming a session still in
     * "proposing" status, or any transition from a terminal state).
     *
     * @return 409 Conflict with error details.
     */
    @ExceptionHandler(InvalidStateTransitionException::class)
    fun handleInvalidStateTransition(
        ex: InvalidStateTransitionException,
    ): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse("INVALID_STATE_TRANSITION", ex.message ?: "Invalid state transition", 409))
    }

    /**
     * Handles cross-instance relay failures.
     *
     * @return 502 Bad Gateway with error details (the remote instance failed).
     */
    @ExceptionHandler(RelayException::class)
    fun handleRelayError(ex: RelayException): ResponseEntity<ErrorResponse> {
        logger.error("Relay error: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("RELAY_ERROR", ex.message ?: "Cross-instance relay failed", 502))
    }

    /**
     * Catch-all handler for unexpected exceptions.
     *
     * Logs the full stack trace for debugging but returns a generic message
     * to the client (no internal details leaked).
     *
     * @return 500 Internal Server Error with generic message.
     */
    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected error", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", 500))
    }
}
