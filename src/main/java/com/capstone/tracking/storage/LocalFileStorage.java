package com.capstone.tracking.storage;

import com.capstone.tracking.common.exception.BadRequestException;
import com.capstone.tracking.common.exception.ResourceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores uploads on the local disk under {@code app.storage.local-dir} (a Docker volume in docker-compose / on Railway). */
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final UploadPolicy uploadPolicy;

    public LocalFileStorage(StorageProperties properties, UploadPolicy uploadPolicy) {
        this.uploadPolicy = uploadPolicy;
        this.root = Path.of(properties.localDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create upload directory " + root, e);
        }
    }

    @Override
    public StoredFile store(MultipartFile file, String directory) {
        UploadPolicy.Accepted accepted = uploadPolicy.accept(file, directory);
        Path target = resolve(accepted.key());
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }
        return new StoredFile(accepted.key(), accepted.originalFilename(), file.getContentType(), file.getSize());
    }

    @Override
    public Resource load(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new ResourceNotFoundException("Stored file not found");
        }
        return new PathResource(path);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file " + key, e);
        }
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new BadRequestException("Invalid storage key");
        }
        return path;
    }
}
