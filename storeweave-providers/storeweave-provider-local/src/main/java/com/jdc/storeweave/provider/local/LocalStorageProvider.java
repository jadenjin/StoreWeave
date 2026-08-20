package com.jdc.storeweave.provider.local;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LocalStorageProvider implements StorageProvider {

    public static final String TYPE = "local";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        URI endpoint = configuration.endpointValue().orElseThrow(() -> new StorageException(
                StorageErrorCode.INVALID_REQUEST,
                "Local storage requires a file endpoint",
                TYPE,
                null,
                false));
        return new LocalStorageClient(configuration.name(), resolvePath(endpoint));
    }

    private static Path resolvePath(URI endpoint) {
        if (endpoint.getScheme() == null) {
            return Path.of(endpoint.toString());
        }
        if (!"file".equalsIgnoreCase(endpoint.getScheme())) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Local endpoint must use the file scheme: " + endpoint,
                    TYPE,
                    null,
                    false);
        }
        return endpoint.isOpaque()
                ? Path.of(endpoint.getSchemeSpecificPart())
                : Paths.get(endpoint);
    }
}
