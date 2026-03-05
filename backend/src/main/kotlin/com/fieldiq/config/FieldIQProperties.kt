package com.fieldiq.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fieldiq")
data class FieldIQProperties(
    val instance: InstanceProperties,
    val jwt: JwtProperties,
    val aws: AwsProperties,
    val google: GoogleProperties = GoogleProperties(),
) {
    data class InstanceProperties(
        val id: String,
        val secret: String,
        val baseUrl: String,
    )

    data class JwtProperties(
        val secret: String,
        val expirationMs: Long = 900_000,
        val refreshExpirationMs: Long = 2_592_000_000,
    )

    data class AwsProperties(
        val endpointUrl: String = "http://localhost:4566",
        val region: String = "us-east-1",
        val sqs: SqsProperties = SqsProperties(),
    ) {
        data class SqsProperties(
            val agentTasksQueue: String = "",
            val notificationsQueue: String = "",
            val negotiationQueue: String = "",
        )
    }

    data class GoogleProperties(
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String = "",
    )
}
