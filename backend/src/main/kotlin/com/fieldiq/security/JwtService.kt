package com.fieldiq.security

import com.fieldiq.config.FieldIQProperties
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import javax.crypto.SecretKey

/**
 * Handles JWT access token generation/validation and refresh token generation.
 *
 * This service is the single source of truth for all token operations in FieldIQ's
 * auth system. It uses HMAC-SHA256 (HS256) for JWT signing, with the secret key
 * configured via [FieldIQProperties.jwt].
 *
 * **Access tokens** are short-lived JWTs (default 15 minutes) containing the user ID
 * in the `sub` claim. Any FieldIQ instance can validate these tokens since they share
 * the same JWT secret — this supports the two-instance dev setup and horizontal scaling.
 *
 * **Refresh tokens** are 32-byte cryptographically random strings. The raw token is
 * returned to the client; only a SHA-256 hash is stored in the database. This service
 * generates the raw tokens and provides hashing — storage is handled by [com.fieldiq.service.AuthService].
 *
 * @property properties Type-safe configuration providing JWT secret and expiration settings.
 * @see JwtAuthenticationFilter for the filter that validates tokens on incoming requests.
 * @see com.fieldiq.service.AuthService for the service that orchestrates the full auth flow.
 */
@Service
class JwtService(
    private val properties: FieldIQProperties,
) {
    private val logger = LoggerFactory.getLogger(JwtService::class.java)
    private val secureRandom = SecureRandom()

    /**
     * Returns the HMAC signing key derived from the configured JWT secret.
     *
     * The secret must be at least 256 bits (32 bytes) for HS256 security.
     * In production, this is set via the `JWT_SECRET` environment variable.
     *
     * @return The [SecretKey] used for both signing and verifying JWTs.
     */
    private fun signingKey(): SecretKey =
        Keys.hmacShaKeyFor(properties.jwt.secret.toByteArray())

    /**
     * Generates a signed JWT access token for the given user.
     *
     * The token contains:
     * - `sub`: User UUID (string)
     * - `type`: "access" (distinguishes from potential future token types)
     * - `iat`: Issued-at timestamp
     * - `exp`: Expiration timestamp (configured via `fieldiq.jwt.expiration-ms`)
     *
     * @param userId The UUID of the authenticated user to encode in the token.
     * @return A signed JWT string suitable for the `Authorization: Bearer` header.
     */
    fun generateAccessToken(userId: UUID): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(properties.jwt.expirationMs)))
            .signWith(signingKey())
            .compact()
    }

    /**
     * Generates a cryptographically random refresh token.
     *
     * The token is 32 bytes of random data, Base64-URL-encoded for safe transport.
     * This raw value is returned to the client exactly once — the server stores
     * only its SHA-256 hash (via [hashToken]).
     *
     * @return A Base64-URL-encoded random string (43 characters, no padding).
     */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Validates a JWT access token and extracts the user ID.
     *
     * Performs full validation: signature verification, expiration check, and
     * claim parsing. Returns null for any validation failure (expired, tampered,
     * malformed) rather than throwing — the caller decides how to handle it.
     *
     * @param token The JWT string from the `Authorization: Bearer` header.
     * @return The user [UUID] from the `sub` claim, or null if validation fails.
     */
    fun validateAccessToken(token: String): UUID? {
        return try {
            val claims = Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .payload

            UUID.fromString(claims.subject)
        } catch (e: JwtException) {
            logger.debug("JWT validation failed: {}", e.message)
            null
        } catch (e: IllegalArgumentException) {
            logger.debug("JWT subject is not a valid UUID: {}", e.message)
            null
        }
    }

    /**
     * Computes a SHA-256 hash of a token string for secure database storage.
     *
     * Used for both auth tokens (OTP codes) and refresh tokens. The raw token
     * is never stored — only this hash. Lookup is performed by hashing the
     * submitted token and querying by hash.
     *
     * @param rawToken The raw token or OTP code to hash.
     * @return Hex-encoded SHA-256 hash of the input.
     */
    fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawToken.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the access token expiration time in seconds.
     *
     * Used in [com.fieldiq.api.dto.AuthResponse.expiresIn] to tell the client
     * how long the access token is valid.
     *
     * @return Expiration time in seconds (e.g., 900 for 15 minutes).
     */
    fun accessTokenExpirationSeconds(): Long =
        properties.jwt.expirationMs / 1000
}
