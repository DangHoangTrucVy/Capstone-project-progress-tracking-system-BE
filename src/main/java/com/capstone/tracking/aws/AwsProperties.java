package com.capstone.tracking.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.aws.*}: shared by the S3 and SQS clients. Leave {@code endpoint} empty for real AWS; point it at
 * Floci ({@code http://localhost:4566}) for local development and CI. When both keys are empty the SDK's default
 * credential chain is used (env vars, profile, instance role).
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
        String region,
        String endpoint,
        String accessKeyId,
        String secretAccessKey
) {
}
