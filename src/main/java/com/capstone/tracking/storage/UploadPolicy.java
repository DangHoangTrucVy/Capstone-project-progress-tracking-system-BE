package com.capstone.tracking.storage;

import com.capstone.tracking.common.exception.ApiException;
import com.capstone.tracking.common.exception.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.UUID;

/** Upload rules shared by every {@link FileStorage}: size limit, allowed extensions, server-generated keys. */
@Component
public class UploadPolicy {

    private final StorageProperties properties;

    public UploadPolicy(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates the upload and returns the key to store it under. The key never contains the client's filename,
     * so a crafted name cannot escape the target directory or bucket prefix.
     */
    public Accepted accept(MultipartFile file, String directory) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded file is empty");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "File exceeds the " + properties.maxFileSize().toMegabytes() + "MB limit");
        }
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = StringUtils.getFilenameExtension(originalName);
        if (extension == null || !properties.allowedExtensions().contains(extension.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("File type not allowed. Allowed: " + String.join(", ", properties.allowedExtensions()));
        }
        String key = directory + "/" + UUID.randomUUID() + "." + extension.toLowerCase(Locale.ROOT);
        return new Accepted(key, originalName);
    }

    public record Accepted(String key, String originalFilename) {
    }
}
