package com.fieldiq.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Request body for `POST /auth/request-otp`.
 *
 * Initiates passwordless authentication by sending a one-time password via SMS or email.
 * Rate limited: max 3 requests per 15-minute window per identifier.
 * Dev bypass: phone numbers matching `+1555*` are exempt from rate limiting.
 *
 * Corresponds to TypeScript interface: `RequestOtpRequest` in `shared/types/index.ts`.
 *
 * @property channel Delivery channel for the OTP code: "sms" or "email".
 * @property value Phone number (E.164 format, e.g., "+12025551234") or email address.
 */
data class RequestOtpRequest(
    @field:NotBlank(message = "Channel is required")
    @field:Pattern(regexp = "sms|email", message = "Channel must be 'sms' or 'email'")
    val channel: String,

    @field:NotBlank(message = "Value is required")
    val value: String,
)

/**
 * Request body for `POST /auth/verify-otp`.
 *
 * Validates the OTP code and returns JWT + refresh token on success.
 * If the user doesn't exist, a new User record is created (sign-up flow).
 * After 5 failed verification attempts, the identifier is blocked for 1 hour.
 *
 * Corresponds to TypeScript interface: `VerifyOtpRequest` in `shared/types/index.ts`.
 *
 * @property channel Must match the channel used in the original OTP request.
 * @property value The phone number or email the OTP was sent to.
 * @property otp The 6-digit OTP code entered by the user.
 */
data class VerifyOtpRequest(
    @field:NotBlank(message = "Channel is required")
    @field:Pattern(regexp = "sms|email", message = "Channel must be 'sms' or 'email'")
    val channel: String,

    @field:NotBlank(message = "Value is required")
    val value: String,

    @field:NotBlank(message = "OTP is required")
    @field:Pattern(regexp = "\\d{6}", message = "OTP must be exactly 6 digits")
    val otp: String,
)

/**
 * Response body returned by `POST /auth/verify-otp` and `POST /auth/refresh`.
 *
 * Contains a short-lived JWT access token and a long-lived refresh token.
 *
 * Corresponds to TypeScript interface: `AuthResponse` in `shared/types/index.ts`.
 *
 * @property accessToken JWT access token for the Authorization header. Expires in 15 minutes.
 * @property refreshToken One-time-use refresh token. Rotated on each use.
 * @property expiresIn Access token lifetime in seconds (900 = 15 minutes).
 * @property user The authenticated user's profile.
 */
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: UserDto,
)

/**
 * Request body for `POST /auth/refresh`.
 *
 * Exchanges a valid refresh token for a new access token + rotated refresh token.
 * The old refresh token is immediately revoked (one-time use).
 *
 * Corresponds to TypeScript interface: `RefreshTokenRequest` in `shared/types/index.ts`.
 *
 * @property refreshToken The refresh token received from a previous auth response.
 */
data class RefreshTokenRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String,
)
