package com.capstone.tracking.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/** {@code app.storage.*} in application.yml. {@code type} picks the {@link FileStorage}: local (default) or s3. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String type,
        String localDir,
        DataSize maxFileSize,
        List<String> allowedExtensions,
        S3 s3
) {
    /** Region and endpoint come from {@code app.aws.*}, shared with SQS. */
    public record S3(String bucket, boolean createBucket) {
    }
}
