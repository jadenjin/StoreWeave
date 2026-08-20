package com.jdc.storeweave.core;

import com.jdc.storeweave.core.capability.StorageCapability;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectMetadata;
import com.jdc.storeweave.core.model.PageResult;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.PutObjectResult;
import com.jdc.storeweave.core.model.StorageObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;

/** Vendor-neutral entry point for object storage operations. */
public interface StorageClient extends AutoCloseable {

    String name();

    String providerType();

    Set<StorageCapabilityType> capabilities();

    PutObjectResult put(PutObjectRequest request);

    InputStream get(StorageObject object);

    ObjectMetadata stat(StorageObject object);

    boolean exists(StorageObject object);

    boolean delete(StorageObject object);

    PageResult<ObjectMetadata> list(ListObjectsRequest request);

    default void download(StorageObject object, Path target) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream input = get(object)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    "Failed to write object to " + target,
                    providerType(),
                    exception,
                    false);
        }
    }

    default <T extends StorageCapability> Optional<T> capability(Class<T> capabilityType) {
        return capabilityType.isInstance(this)
                ? Optional.of(capabilityType.cast(this))
                : Optional.empty();
    }

    @Override
    default void close() {
        // Most providers do not require explicit cleanup.
    }
}
