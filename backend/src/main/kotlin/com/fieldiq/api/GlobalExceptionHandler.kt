package com.fieldiq.api

import com.fieldiq.api.dto.ErrorResponse
import com.fieldiq.service.InvalidOtpException
import com.fieldiq.service.RateLimitExceededException
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

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
