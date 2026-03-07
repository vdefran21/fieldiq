package com.fieldiq.security

import com.fieldiq.config.FieldIQProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles HMAC-SHA256 cryptographic operations for cross-instance negotiation auth.
 *
 * This service is the foundation of FieldIQ's cross-instance security model. When two
 * FieldIQ instances negotiate a game time, every relay request between them is signed
 * with a per-session HMAC key. This prevents tampering, replay attacks, and unauthorized
 * relay calls.
 *
 * **Key derivation:**
 * ```
 * sessionKey = HMAC-SHA256(instanceSecret, inviteToken)
 * ```
 * Both instances independently derive the same session key because they both know the
 * instance secret (shared out-of-band) and the invite token (exchanged during the join
 * handshake). The invite token is consumed after join, so the key cannot be re-derived
 * by an attacker who intercepts a later relay call.
 *
 * **Signature computation:**
 * ```
 * message = sessionId + timestamp + requestBody
 * signature = HMAC-SHA256(sessionKey, message)
 * ```
 * The signature covers the full request payload plus a timestamp to prevent replay.
 *
 * **Validation rules (per Doc 04):**
 * - Reject if `|server_time - timestamp| > 5 minutes`
 * - Reject if signature does not match
 * - Replay prevention is handled externally via Redis nonce (see [HmacAuthenticationFilter])
 *
 * @property properties FieldIQ config containing the instance secret used for key derivation.
 * @see HmacAuthenticationFilter for the incoming request validation filter.
 * @see CrossInstanceRelayClient for the outbound HTTP client that signs requests.
 */
@Service
class HmacService(
    private val properties: FieldIQProperties,
) {

    private val logger = LoggerFactory.getLogger(HmacService::class.java)

    companion object {
        /** HMAC algorithm used for both key derivation and signing. */
        const val ALGORITHM = "HmacSHA256"

        /** Maximum allowed clock drift between instances, per Doc 04 spec. */
        val MAX_TIMESTAMP_DRIFT: Duration = Duration.ofMinutes(5)
    }

    /**
     * Derives a per-session HMAC key from the instance secret and invite token.
     *
     * Called once when a negotiation session transitions from `pending_response` to
     * `proposing` (i.e., when the responder joins). Both instances derive the same
     * key independently — the initiator derives it when creating the session, and
     * the responder derives it when joining.
     *
     * The derived key is never persisted directly; only its SHA-256 hash is stored
     * in [com.fieldiq.domain.NegotiationSession.sessionKeyHash] for audit purposes.
     *
     * @param inviteToken The single-use invite token from the negotiation session.
     *   Must be the original token value (before consumption/nullification).
     * @return The derived session key as a raw byte array. Callers should use this
     *   with [sign] and [validate], then discard it (do not persist).
     */
    fun deriveSessionKey(inviteToken: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(properties.instance.secret.toByteArray(), ALGORITHM))
        return mac.doFinal(inviteToken.toByteArray())
    }

    /**
     * Computes an HMAC-SHA256 signature for an outbound relay request.
     *
     * The message is constructed by concatenating the session ID, timestamp, and
     * request body (in that order, no delimiters). This matches the spec in Doc 04:
     * `message = sessionId + timestamp + requestBodyString`.
     *
     * @param sessionKey The per-session HMAC key from [deriveSessionKey].
     * @param sessionId The negotiation session UUID as a string.
     * @param timestamp ISO-8601 UTC timestamp of the request.
     * @param body The raw JSON request body string.
     * @return The HMAC signature as a lowercase hex string.
     */
    fun sign(sessionKey: ByteArray, sessionId: String, timestamp: String, body: String): String {
        val message = sessionId + timestamp + body
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(sessionKey, ALGORITHM))
        return mac.doFinal(message.toByteArray()).toHexString()
    }

    /**
     * Validates an incoming relay request's HMAC signature and timestamp.
     *
     * Performs two checks in order:
     * 1. **Timestamp drift:** Rejects if the request timestamp is more than ±5 minutes
     *    from the server's current time. This limits the replay window.
     * 2. **Signature match:** Recomputes the expected signature and compares it to the
     *    provided signature using constant-time comparison (via [MessageDigest.isEqual])
     *    to prevent timing attacks.
     *
     * Replay prevention (nonce checking) is NOT handled here — it's the responsibility
     * of [HmacAuthenticationFilter] which uses Redis for nonce storage.
     *
     * @param sessionKey The per-session HMAC key from [deriveSessionKey].
     * @param sessionId The session ID from the `X-FieldIQ-Session-Id` header.
     * @param timestamp The timestamp from the `X-FieldIQ-Timestamp` header.
     * @param body The raw request body.
     * @param signature The signature from the `X-FieldIQ-Signature` header.
     * @return `true` if both timestamp and signature are valid, `false` otherwise.
     */
    fun validate(
        sessionKey: ByteArray,
        sessionId: String,
        timestamp: String,
        body: String,
        signature: String,
    ): Boolean {
        // Check timestamp drift
        val requestTime = try {
            Instant.parse(timestamp)
        } catch (e: Exception) {
            logger.warn("Invalid timestamp format in relay request: {}", timestamp)
            return false
        }

        val drift = Duration.between(requestTime, Instant.now()).abs()
        if (drift > MAX_TIMESTAMP_DRIFT) {
            logger.warn(
                "Relay request timestamp drift too large: {} (max {})",
                drift,
                MAX_TIMESTAMP_DRIFT,
            )
            return false
        }

        // Compute expected signature and compare using constant-time equality
        val expected = sign(sessionKey, sessionId, timestamp, body)
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(),
            signature.toByteArray(),
        )
    }

    /**
     * Converts a byte array to a lowercase hexadecimal string.
     *
     * @return The hex-encoded string representation of the byte array.
     */
    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
