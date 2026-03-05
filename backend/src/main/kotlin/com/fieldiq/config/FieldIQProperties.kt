package com.fieldiq.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Type-safe configuration properties for FieldIQ, bound from `application.yml`.
 *
 * All FieldIQ-specific configuration lives under the `fieldiq.*` YAML namespace.
 * This class provides compile-time safety and IDE autocompletion for configuration
 * values, avoiding raw `@Value` string lookups scattered across the codebase.
 *
 * Properties are organized into nested groups matching the YAML hierarchy:
 * - `fieldiq.instance.*` — per-instance identity and secrets
 * - `fieldiq.jwt.*` — JWT token generation parameters
 * - `fieldiq.aws.*` — AWS SDK / LocalStack configuration
 * - `fieldiq.google.*` — Google OAuth credentials for calendar integration
 *
 * Activated by [AppConfig] via `@EnableConfigurationProperties`.
 *
 * @property instance Per-instance configuration (ID, HMAC secret, base URL).
 * @property jwt JWT token generation and validation settings.
 * @property aws AWS SDK configuration (SQS queues, endpoint URL for LocalStack).
 * @property google Google OAuth client credentials for Calendar integration.
 * @see AppConfig for the configuration class that activates these properties.
 */
@ConfigurationProperties(prefix = "fieldiq")
data class FieldIQProperties(
    val instance: InstanceProperties,
    val jwt: JwtProperties,
    val aws: AwsProperties,
    val google: GoogleProperties = GoogleProperties(),
) {

    /**
     * Identity and security configuration for this FieldIQ instance.
     *
     * Each FieldIQ deployment has a unique instance ID and a shared secret used
     * to derive HMAC session keys for cross-instance negotiation authentication.
     * In local dev, two instances run on ports 8080 and 8081 with different IDs.
     *
     * @property id Unique identifier for this instance (e.g., "instance-a", "team-a-local").
     *   Used in negotiation session records to identify which instance initiated.
     * @property secret Shared secret used to derive per-session HMAC keys via
     *   `HMAC-SHA256(secret, invite_token)`. MUST be kept secret and rotated in production.
     *   In dev, set via `FIELDIQ_INSTANCE_SECRET` env var.
     * @property baseUrl The publicly reachable base URL of this instance
     *   (e.g., "http://localhost:8080"). Used by remote instances to send relay
     *   requests back to this instance during negotiations.
     */
    data class InstanceProperties(
        val id: String,
        val secret: String,
        val baseUrl: String,
    )

    /**
     * JWT token configuration for user authentication.
     *
     * FieldIQ uses short-lived JWT access tokens paired with long-lived refresh
     * tokens. The access token is sent in the `Authorization: Bearer` header.
     * Refresh tokens are rotated on each use (one-time use with chain tracking
     * via [com.fieldiq.domain.RefreshToken.rotatedFrom]).
     *
     * @property secret HMAC secret for signing JWT tokens. MUST be at least 256 bits
     *   in production. Set via `JWT_SECRET` env var.
     * @property expirationMs Access token lifetime in milliseconds. Default: 15 minutes (900,000ms).
     * @property refreshExpirationMs Refresh token lifetime in milliseconds. Default: 30 days.
     */
    data class JwtProperties(
        val secret: String,
        val expirationMs: Long = 900_000,
        val refreshExpirationMs: Long = 2_592_000_000,
    )

    /**
     * AWS SDK configuration, supporting both real AWS and LocalStack for local dev.
     *
     * In local development, all AWS services are provided by LocalStack on port 4566.
     * The [endpointUrl] override makes the AWS SDK client point to LocalStack instead
     * of real AWS endpoints.
     *
     * @property endpointUrl AWS endpoint URL. Set to LocalStack URL in dev, omit in prod
     *   to use real AWS endpoints.
     * @property region AWS region for SQS and other services. Default: us-east-1.
     * @property sqs SQS queue URL configuration.
     */
    data class AwsProperties(
        val endpointUrl: String = "http://localhost:4566",
        val region: String = "us-east-1",
        val sqs: SqsProperties = SqsProperties(),
    ) {

        /**
         * SQS queue URLs for backend → agent communication.
         *
         * The backend enqueues tasks to these queues; the agent layer consumes them.
         * Queue URLs differ between LocalStack (localhost) and production (real SQS).
         *
         * @property agentTasksQueue URL for the general agent task queue (calendar sync, etc.).
         * @property notificationsQueue URL for the notification dispatch queue (push, SMS, email).
         * @property negotiationQueue URL for negotiation-specific async tasks.
         */
        data class SqsProperties(
            val agentTasksQueue: String = "",
            val notificationsQueue: String = "",
            val negotiationQueue: String = "",
        )
    }

    /**
     * Google OAuth credentials for Calendar integration.
     *
     * Used during the Google Calendar OAuth flow to obtain read-only access to
     * users' calendars. Phase 1 uses `calendar.readonly` scope only — no write-back.
     * Credentials are obtained from the Google Cloud Console.
     *
     * @property clientId Google OAuth client ID from Cloud Console.
     * @property clientSecret Google OAuth client secret. MUST be kept secret;
     *   set via `GOOGLE_CLIENT_SECRET` env var, never committed to source.
     * @property redirectUri OAuth callback URL that Google redirects to after consent.
     *   Must match exactly what's registered in Cloud Console.
     */
    data class GoogleProperties(
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String = "",
    )
}
