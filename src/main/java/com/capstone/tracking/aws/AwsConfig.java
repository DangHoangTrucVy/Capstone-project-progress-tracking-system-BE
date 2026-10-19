package com.capstone.tracking.aws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/** AWS SDK clients, created only when a feature is switched to AWS (messaging type sqs). */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.messaging", name = "type", havingValue = "sqs")
    public SqsClient sqsClient(AwsProperties aws) {
        var builder = SqsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(aws.region()))
                .credentialsProvider(credentials(aws));
        if (StringUtils.hasText(aws.endpoint())) {
            builder.endpointOverride(URI.create(aws.endpoint()));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentials(AwsProperties aws) {
        if (StringUtils.hasText(aws.accessKeyId()) && StringUtils.hasText(aws.secretAccessKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
