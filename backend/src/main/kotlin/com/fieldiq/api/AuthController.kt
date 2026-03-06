package com.fieldiq.api

import com.fieldiq.api.dto.AuthResponse
import com.fieldiq.api.dto.RefreshTokenRequest
import com.fieldiq.api.dto.RequestOtpRequest
import com.fieldiq.api.dto.VerifyOtpRequest
import com.fieldiq.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller for passwordless OTP authentication.
 *
 * Handles the full auth lifecycle: OTP request, OTP verification (with auto
 * sign-up), token refresh (rotation), and logout. All endpoints under /auth
 * are public (no JWT required) per [com.fieldiq.security.SecurityConfig].
 *
 * Flow:
 * 1. Client calls [requestOtp] with phone/email to receive a 6-digit code
 * 2. Client calls [verifyOtp] with the code to get JWT + refresh token
 * 3. Client uses JWT for all authenticated requests
 * 4. When JWT expires, client calls [refresh] with refresh token for new pair
 * 5. On sign-out, client calls [logout] to revoke the refresh token
 *
 * @property authService Business logic for OTP and token operations.
 * @see AuthService for the underlying business logic.
 * @see com.fieldiq.security.JwtService for token generation.
 */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {

    /**
     * Initiates passwordless auth by sending an OTP to the user's phone or email.
     *
     * Rate limited: 3 per 15 minutes, 10 per 24 hours per identifier.
     * Dev bypass: +1555 phone numbers skip rate limiting and always get OTP "000000".
     *
     * @param request The channel ("sms"/"email") and identifier (phone/email).
     * @return 200 OK on success. The OTP is logged in dev (not actually sent in Phase 1).
     */
    @PostMapping("/request-otp")
    fun requestOtp(@Valid @RequestBody request: RequestOtpRequest): ResponseEntity<Void> {
        authService.requestOtp(request.channel, request.value)
        return ResponseEntity.ok().build()
    }

    /**
     * Verifies an OTP code and returns JWT + refresh token.
     *
     * If the user does not exist, a new account is auto-created (sign-up flow).
     * The access token expires in 15 minutes; the refresh token is valid for 30 days.
     *
     * @param request The channel, identifier, and 6-digit OTP code.
     * @return 200 OK with [AuthResponse] containing tokens and user profile.
     */
    @PostMapping("/verify-otp")
    fun verifyOtp(@Valid @RequestBody request: VerifyOtpRequest): ResponseEntity<AuthResponse> {
        val response = authService.verifyOtp(request.channel, request.value, request.otp)
        return ResponseEntity.ok(response)
    }

    /**
     * Exchanges a valid refresh token for a new access + refresh token pair.
     *
     * The old refresh token is immediately revoked (one-time use with rotation).
     *
     * @param request Contains the refresh token from a previous auth response.
     * @return 200 OK with [AuthResponse] containing new tokens and user profile.
     */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<AuthResponse> {
        val response = authService.refreshToken(request.refreshToken)
        return ResponseEntity.ok(response)
    }

    /**
     * Revokes a refresh token, ending the user's session.
     *
     * Idempotent: calling with an already-revoked or invalid token is a no-op.
     *
     * @param request Contains the refresh token to revoke.
     * @return 204 No Content on success.
     */
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: RefreshTokenRequest): ResponseEntity<Void> {
        authService.logout(request.refreshToken)
        return ResponseEntity.noContent().build()
    }
}
