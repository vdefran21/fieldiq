package com.fieldiq.service

import com.fieldiq.api.dto.AuthResponse
import com.fieldiq.api.dto.UserDto
import com.fieldiq.domain.AuthToken
import com.fieldiq.domain.RefreshToken
import com.fieldiq.domain.User
import com.fieldiq.repository.AuthTokenRepository
import com.fieldiq.repository.RefreshTokenRepository
import com.fieldiq.repository.UserRepository
import com.fieldiq.security.JwtService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Orchestrates the passwordless OTP authentication flow.
 *
 * Handles the full auth lifecycle: OTP request, OTP verification (with auto sign-up),
 * JWT access token issuance, refresh token rotation, and logout. This is the core
 * service backing all /auth endpoints.
 *
 * OTP codes are 6-digit random numbers, hashed with SHA-256 before storage.
 * Refresh tokens are 32-byte random values, also hashed before storage.
 * Neither raw value is ever persisted.
 *
 * **Identifier binding:** Each OTP token is bound to the normalized identifier (phone
 * or email) it was requested for. The identifier is normalized ([normalizeIdentifier])
 * and hashed before storage. On verification, the same normalization and hashing is
 * applied to the submitted identifier, and the lookup query includes the identifier
 * hash. This prevents a valid OTP for one identity from being used to authenticate
 * as a different identity on the same channel.
 *
 * External SMS/email sending is stubbed in Phase 1 (logs to console).
 * Dev bypass: identifiers starting with "+1555" accept OTP "000000".
 *
 * @property userRepository Repository for user lookup and creation.
 * @property authTokenRepository Repository for OTP token storage.
 * @property refreshTokenRepository Repository for refresh token storage.
 * @property jwtService Service for JWT and token hash operations.
 * @property rateLimitService Service for OTP rate limiting enforcement.
 * @see com.fieldiq.api.AuthController for the REST endpoints using this service.
 * @see JwtService for token generation and hashing.
 */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val authTokenRepository: AuthTokenRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val rateLimitService: OtpRateLimitService,
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val random = Random()

    companion object {
        private const val OTP_LENGTH = 6
        private const val OTP_EXPIRY_MINUTES = 10L
        private const val DEV_OTP = "000000"
    }

    /**
     * Initiates the OTP flow by generating and "sending" a code.
     *
     * Validates the identifier format, checks rate limits, generates a 6-digit
     * OTP, hashes it, and stores it in auth_tokens along with a hash of the
     * normalized identifier. This identifier binding ensures the OTP can only
     * be verified by the same phone/email that requested it.
     *
     * In production, the raw OTP would be sent via Twilio (SMS) or SendGrid
     * (email). In Phase 1, it is logged to the console.
     *
     * Dev bypass: for "+1555" phone numbers, the OTP "000000" is always used
     * and rate limiting is skipped.
     *
     * @param channel "sms" or "email".
     * @param identifier Phone (E.164) or email address.
     * @throws RateLimitExceededException If the identifier has exceeded OTP rate limits.
     * @throws IllegalArgumentException If the channel or identifier format is invalid.
     */
    @Transactional
    fun requestOtp(channel: String, identifier: String) {
        validateIdentifier(channel, identifier)

        rateLimitService.checkRateLimit(identifier)

        val normalizedId = normalizeIdentifier(channel, identifier)
        val otp = if (identifier.startsWith("+1555")) DEV_OTP else generateOtp()
        val hashedOtp = jwtService.hashToken(otp)
        val identifierHash = jwtService.hashToken(normalizedId)

        val authToken = AuthToken(
            tokenHash = hashedOtp,
            identifierHash = identifierHash,
            channel = channel,
            expiresAt = Instant.now().plus(OTP_EXPIRY_MINUTES, ChronoUnit.MINUTES),
        )
        authTokenRepository.save(authToken)

        rateLimitService.recordAttempt(identifier)

        // Phase 1: Log OTP to console instead of sending via Twilio/SendGrid
        logger.info("OTP for {} ({}): {}", channel, identifier, otp)
    }

    /**
     * Verifies an OTP code and returns JWT + refresh token on success.
     *
     * Validates the identifier format, normalizes and hashes it, then looks up
     * the auth token by channel + OTP hash + identifier hash. The identifier hash
     * binding ensures the submitted OTP can only authenticate the same phone/email
     * that originally requested it.
     *
     * If the token is found and not expired, it is marked as used. If the user
     * does not exist, a new User record is created (auto sign-up). Issues a JWT
     * access token and a refresh token.
     *
     * @param channel "sms" or "email".
     * @param identifier Phone (E.164) or email address.
     * @param otp The 6-digit code submitted by the user.
     * @return [AuthResponse] with access token, refresh token, and user profile.
     * @throws InvalidOtpException If the OTP is invalid, expired, or already used.
     * @throws IllegalArgumentException If the channel or identifier format is invalid.
     */
    @Transactional
    fun verifyOtp(channel: String, identifier: String, otp: String): AuthResponse {
        validateIdentifier(channel, identifier)

        val normalizedId = normalizeIdentifier(channel, identifier)
        val hashedOtp = jwtService.hashToken(otp)
        val identifierHash = jwtService.hashToken(normalizedId)

        val authToken = authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
            channel, hashedOtp, identifierHash
        ) ?: throw InvalidOtpException("Invalid or expired OTP")

        if (authToken.expiresAt.isBefore(Instant.now())) {
            throw InvalidOtpException("OTP has expired")
        }

        // Mark token as used
        authTokenRepository.save(authToken.copy(usedAt = Instant.now()))

        // Find or create user
        val user = findOrCreateUser(channel, identifier)

        return issueTokens(user)
    }

    /**
     * Refreshes an expired access token using a valid refresh token.
     *
     * Looks up the refresh token by its hash, verifies it is not revoked or
     * expired, then revokes it and issues a new access/refresh token pair.
     * This implements refresh token rotation (one-time use).
     *
     * @param rawRefreshToken The raw refresh token string from the client.
     * @return [AuthResponse] with new access token, refresh token, and user profile.
     * @throws InvalidOtpException If the refresh token is invalid, revoked, or expired.
     */
    @Transactional
    fun refreshToken(rawRefreshToken: String): AuthResponse {
        val tokenHash = jwtService.hashToken(rawRefreshToken)

        val existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
            ?: throw InvalidOtpException("Invalid refresh token")

        if (existingToken.revokedAt != null) {
            logger.warn("Attempted use of revoked refresh token for user {}", existingToken.userId)
            throw InvalidOtpException("Refresh token has been revoked")
        }

        if (existingToken.expiresAt.isBefore(Instant.now())) {
            throw InvalidOtpException("Refresh token has expired")
        }

        // Revoke old token
        refreshTokenRepository.save(existingToken.copy(revokedAt = Instant.now()))

        val user = userRepository.findById(existingToken.userId)
            .orElseThrow { InvalidOtpException("User not found") }

        return issueTokens(user, rotatedFrom = existingToken.id)
    }

    /**
     * Revokes a refresh token, ending the user's session.
     *
     * The token is looked up by its hash and marked as revoked. If the token
     * is not found or already revoked, the operation is a no-op (idempotent).
     *
     * @param rawRefreshToken The raw refresh token string from the client.
     */
    @Transactional
    fun logout(rawRefreshToken: String) {
        val tokenHash = jwtService.hashToken(rawRefreshToken)
        val token = refreshTokenRepository.findByTokenHash(tokenHash) ?: return

        if (token.revokedAt == null) {
            refreshTokenRepository.save(token.copy(revokedAt = Instant.now()))
        }
    }

    /**
     * Issues a new JWT access token and refresh token pair for the given user.
     *
     * @param user The authenticated user.
     * @param rotatedFrom UUID of the previous refresh token in the chain, if rotating.
     * @return [AuthResponse] containing the tokens and user profile.
     */
    private fun issueTokens(user: User, rotatedFrom: UUID? = null): AuthResponse {
        val accessToken = jwtService.generateAccessToken(user.id)
        val rawRefreshToken = jwtService.generateRefreshToken()
        val refreshTokenHash = jwtService.hashToken(rawRefreshToken)

        val refreshToken = RefreshToken(
            userId = user.id,
            tokenHash = refreshTokenHash,
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
            rotatedFrom = rotatedFrom,
        )
        refreshTokenRepository.save(refreshToken)

        return AuthResponse(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            expiresIn = jwtService.accessTokenExpirationSeconds(),
            user = UserDto.from(user),
        )
    }

    /**
     * Finds an existing user by phone or email, or creates a new one.
     *
     * This implements the auto sign-up flow: the first time a phone/email is
     * verified, a User record is automatically created. No separate registration
     * step is needed.
     *
     * @param channel "sms" or "email".
     * @param identifier The phone or email to look up.
     * @return The existing or newly created [User].
     */
    private fun findOrCreateUser(channel: String, identifier: String): User {
        return when (channel) {
            "sms" -> userRepository.findByPhone(identifier)
                ?: userRepository.save(User(phone = identifier))
            "email" -> userRepository.findByEmail(identifier.lowercase())
                ?: userRepository.save(User(email = identifier.lowercase()))
            else -> throw IllegalArgumentException("Invalid channel: $channel")
        }
    }

    /**
     * Generates a random 6-digit OTP code.
     *
     * @return A zero-padded 6-digit string (e.g., "042519").
     */
    private fun generateOtp(): String {
        val code = random.nextInt(1_000_000)
        return code.toString().padStart(OTP_LENGTH, '0')
    }

    /**
     * Validates that the identifier matches the expected format for the channel.
     *
     * @param channel "sms" or "email".
     * @param identifier The phone or email to validate.
     * @throws IllegalArgumentException If the format is invalid.
     */
    private fun validateIdentifier(channel: String, identifier: String) {
        when (channel) {
            "sms" -> {
                if (!identifier.startsWith("+") || identifier.length < 10) {
                    throw IllegalArgumentException("Phone must be in E.164 format (e.g., +12025551234)")
                }
            }
            "email" -> {
                if (!identifier.contains("@") || !identifier.contains(".")) {
                    throw IllegalArgumentException("Invalid email format")
                }
            }
            else -> throw IllegalArgumentException("Channel must be 'sms' or 'email'")
        }
    }

    /**
     * Normalizes an identifier for consistent hashing across request and verify flows.
     *
     * Ensures the same phone/email always produces the same SHA-256 hash regardless
     * of casing or whitespace in the original input:
     * - **Email:** lowercased and trimmed (e.g., " User@Example.COM " → "user@example.com")
     * - **Phone:** trimmed only (already in E.164 format from [validateIdentifier])
     *
     * This normalization is applied in both [requestOtp] and [verifyOtp] before hashing,
     * so the identifier hash stored on the token always matches the hash computed during
     * verification for the same logical identity.
     *
     * @param channel "sms" or "email".
     * @param identifier The raw phone or email from the request.
     * @return The normalized identifier string, ready for hashing.
     */
    private fun normalizeIdentifier(channel: String, identifier: String): String {
        return when (channel) {
            "email" -> identifier.lowercase().trim()
            else -> identifier.trim()
        }
    }
}

/**
 * Thrown when OTP verification or refresh token validation fails.
 *
 * Caught by [com.fieldiq.api.GlobalExceptionHandler] and returned as 401 Unauthorized.
 *
 * @property message Description of what went wrong.
 */
class InvalidOtpException(message: String) : RuntimeException(message)
