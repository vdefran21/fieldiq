package com.fieldiq.api.dto

/**
 * Standard error response envelope returned by all API error handlers.
 *
 * Provides a consistent error format across all endpoints, making it easy
 * for the mobile client to parse and display error messages. Used by
 * [com.fieldiq.api.GlobalExceptionHandler] to wrap exceptions into
 * structured HTTP error responses.
 *
 * Corresponds to the error handling pattern expected by the React Native
 * API client in `mobile/services/api.ts`.
 *
 * @property error Short error code or type (e.g., "UNAUTHORIZED", "RATE_LIMITED").
 * @property message Human-readable description of what went wrong.
 * @property status HTTP status code (e.g., 401, 429, 500).
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val status: Int,
)
