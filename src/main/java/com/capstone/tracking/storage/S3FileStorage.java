package com.capstone.tracking.storage;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * TS-22 / COMP-17: stores uploads in an S3 bucket ({@code app.storage.type=s3}). Works against real AWS or, with
 * {@code app.aws.endpoint} set, against Floci locally and in CI.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3")
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final UploadPolicy uploadPolicy;
    private final StorageProperties.S3 config;

    public S3FileStorage(S3Client s3, UploadPolicy uploadPolicy, StorageProperties properties) {
        this.s3 = s3;
        this.uploadPolicy = uploadPolicy;
        this.config = properties.s3();
    }

    /** For emulators and fresh dev accounts; production buckets should be provisioned outside the app. */
    @PostConstruct
    void ensureBucket() {
        if (!config.createBucket()) {
            return;
        }
        try {
            s3.headBucket(b -> b.bucket(config.bucket()));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(config.bucket()));
            log.info("Created S3 bucket {}", config.bucket());
        }
    }

    @Override
    public StoredFile store(MultipartFile file, String directory) {
        UploadPolicy.Accepted accepted = uploadPolicy.accept(file, directory);
        try (InputStream in = file.getInputStream()) {
            s3.putObject(b -> b.bucket(config.bucket()).key(accepted.key()).contentType(file.getContentType()),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        return new StoredFile(accepted.key(), accepted.originalFilename(), file.getContentType(), file.getSize());
    }

    @Override
    public Resource load(String key) {
        try {
            return new InputStreamResource(s3.getObject(b -> b.bucket(config.bucket()).key(key)));
        } catch (NoSuchKeyException e) {
            throw new ResourceNotFoundException("Stored file not found");
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(config.bucket()).key(key));
    }
}
