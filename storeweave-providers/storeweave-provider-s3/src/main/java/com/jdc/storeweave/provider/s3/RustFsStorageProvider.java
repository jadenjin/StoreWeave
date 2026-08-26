package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;

import java.net.URI;
import java.util.Locale;

/** RustFS preset for the S3 protocol implementation. */
public final class RustFsStorageProvider implements StorageProvider {

    public static final String TYPE = "rustfs";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("RustFS provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("RustFS endpoint is required"));
        validateEndpoint(endpoint);
        configuration.credentialsValue()
                .orElseThrow(() -> invalid("RustFS access-key and secret-key are required"));
        return S3StorageProvider.createClient(configuration, TYPE, true);
    }

    private static void validateEndpoint(URI endpoint) {
        S3StorageProvider.validateEndpoint(endpoint, TYPE);
        if (endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null
                && !endpoint.getPath().isBlank()
                && !"/".equals(endpoint.getPath()))) {
            throw invalid("RustFS endpoint must not contain credentials, a path, query, or fragment");
        }
    }

    private static StorageException invalid(String message) {
        return S3StorageProvider.invalid(TYPE, message);
    }
}
