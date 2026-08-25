package com.jdc.storeweave.provider.aliyun.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;

import java.net.URI;
import java.util.Locale;

public final class AliyunOssStorageProvider implements StorageProvider {

    public static final String TYPE = "aliyun-oss";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String SOCKET_TIMEOUT_MS = "socket-timeout-ms";
    public static final String MAX_ERROR_RETRY = "max-error-retry";
    public static final String SLD_ENABLED = "sld-enabled";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 50_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 50_000;
    private static final int DEFAULT_MAX_ERROR_RETRY = 3;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("Aliyun OSS provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("Aliyun OSS endpoint is required"));
        validateEndpoint(endpoint);
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("Aliyun OSS access key and secret key are required"));

        ClientBuilderConfiguration clientConfiguration = new ClientBuilderConfiguration();
        clientConfiguration.setConnectionTimeout(intOption(
                configuration, CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS, 1));
        clientConfiguration.setSocketTimeout(intOption(
                configuration, SOCKET_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS, 1));
        clientConfiguration.setMaxErrorRetry(intOption(
                configuration, MAX_ERROR_RETRY, DEFAULT_MAX_ERROR_RETRY, 0));
        clientConfiguration.setSLDEnabled(booleanOption(configuration, SLD_ENABLED, false));

        try {
            DefaultCredentialProvider credentialProvider = new DefaultCredentialProvider(
                    credentials.accessKey(),
                    credentials.secretKey(),
                    credentials.sessionToken().orElse(null));
            OSS client = new OSSClientBuilder().build(
                    endpoint.toString(), credentialProvider, clientConfiguration);
            return new AliyunOssStorageClient(configuration.name(), client);
        } catch (RuntimeException exception) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Cannot create Aliyun OSS client",
                    TYPE,
                    exception,
                    false);
        }
    }

    private static int intOption(
            StorageConfiguration configuration,
            String name,
            int defaultValue,
            int minimum) {
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
            StorageConfiguration configuration,
            String name,
            boolean defaultValue) {
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
            throw invalid("Aliyun OSS endpoint must be an absolute HTTP(S) URI");
        }
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, TYPE, null, false);
    }
}
