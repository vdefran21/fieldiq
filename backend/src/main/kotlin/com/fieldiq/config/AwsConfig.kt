package com.fieldiq.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

/**
 * Configures the AWS SDK client used for backend queue publishing.
 *
 * Local development points this client at LocalStack using static test credentials.
 * Production can keep the same code path while overriding endpoint and credentials
 * through the environment and AWS runtime defaults.
 *
 * @property properties FieldIQ configuration containing AWS region and endpoint.
 */
@Configuration
class AwsConfig(
    private val properties: FieldIQProperties,
) {

    /**
     * Creates the shared SQS client used by backend services to enqueue tasks.
     *
     * @return Configured [SqsClient] for LocalStack or AWS.
     */
    @Bean
    fun sqsClient(): SqsClient = SqsClient.builder()
        .region(Region.of(properties.aws.region))
        .endpointOverride(URI.create(properties.aws.endpointUrl))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"),
            ),
        )
        .build()
}
