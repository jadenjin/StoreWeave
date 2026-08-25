package com.jdc.storeweave.provider.huawei.obs;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;
import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;

import java.net.URI;
import java.util.Locale;

public final class HuaweiObsStorageProvider implements StorageProvider {

    public static final String TYPE = "huawei-obs";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String SOCKET_TIMEOUT_MS = "socket-timeout-ms";
    public static final String MAX_ERROR_RETRY = "max-error-retry";
    public static final String PATH_STYLE_ACCESS = "path-style-access";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_MAX_ERROR_RETRY = 3;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("Huawei OBS provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("Huawei OBS endpoint is required"));
        validateEndpoint(endpoint);
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("Huawei OBS access key and secret key are required"));

        ObsConfiguration sdkConfiguration = new ObsConfiguration();
        sdkConfiguration.setEndPoint(endpoint.toString());
        sdkConfiguration.setConnectionTimeout(intOption(
                configuration, CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS, 1));
        sdkConfiguration.setSocketTimeout(intOption(
                configuration, SOCKET_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS, 1));
        sdkConfiguration.setMaxErrorRetry(intOption(
                configuration, MAX_ERROR_RETRY, DEFAULT_MAX_ERROR_RETRY, 0));
        sdkConfiguration.setPathStyle(booleanOption(configuration, PATH_STYLE_ACCESS, false));

        try {
            ObsClient client = credentials.sessionToken()
                    .map(token -> new ObsClient(
                            credentials.accessKey(), credentials.secretKey(), token, sdkConfiguration))
                    .orElseGet(() -> new ObsClient(
                            credentials.accessKey(), credentials.secretKey(), sdkConfiguration));
            return new HuaweiObsStorageClient(
                    configuration.name(), configuration.regionValue().orElse(null), client);
        } catch (RuntimeException exception) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Cannot create Huawei OBS client",
                    TYPE,
                    exception,
                    false);
        }
    }

    private static int intOption(
            StorageConfiguration configuration, String name, int defaultValue, int minimum) {
        return configuration.option(name).map(value -> {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw invalid("Option '" + name + "' must be an integer greater than or equal to " + minimum);
            }
        }).orElse(defaultValue);
    }

    private static boolean booleanOption(
            StorageConfiguration configuration, String name, boolean defaultValue) {
        return configuration.option(name).map(value -> {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw invalid("Option '" + name + "' must be true or false");
        }).orElse(defaultValue);
    }

    private static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw invalid("Huawei OBS endpoint must be an absolute HTTP(S) URI");
        }
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, TYPE, null, false);
    }
}
