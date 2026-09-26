package com.capstone.tracking.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/** {@code app.storage.*} in application.yml. */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String localDir,
        DataSize maxFileSize,
        List<String> allowedExtensions
) {
}
