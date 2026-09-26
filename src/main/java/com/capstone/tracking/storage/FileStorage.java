package com.capstone.tracking.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * COMP-17 storage port (04-ADD.md). v1 is {@link LocalFileStorage}; an S3/MinIO adapter (TS-22) can replace it
 * without touching callers, which only ever hold the opaque storage key.
 */
public interface FileStorage {

    /** Validates and stores the file under {@code directory}; returns the key to load it back later. */
    StoredFile store(MultipartFile file, String directory);

    Resource load(String key);

    void delete(String key);

    record StoredFile(String key, String originalFilename, String contentType, long sizeBytes) {
    }
}
