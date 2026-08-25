package com.jdc.storeweave.provider.tencent.cos;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;

import java.util.Locale;

public final class TencentCosStorageProvider implements StorageProvider {

    public static final String TYPE = "tencent-cos";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String SOCKET_TIMEOUT_MS = "socket-timeout-ms";
    public static final String MAX_ERROR_RETRY = "max-error-retry";
    public static final String PROTOCOL = "protocol";

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
            throw invalid("Tencent COS provider cannot create storage type: " + configuration.type());
        }
        String region = configuration.regionValue()
                .orElseThrow(() -> invalid("Tencent COS region is required"));
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("Tencent COS access key and secret key are required"));

        ClientConfig clientConfig = new ClientConfig(new Region(region));
        clientConfig.setConnectionTimeout(intOption(
                configuration, CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS, 1));
        clientConfig.setSocketTimeout(intOption(
                configuration, SOCKET_TIMEOUT_MS, DEFAULT_SOCKET_TIMEOUT_MS, 1));
        clientConfig.setMaxErrorRetry(intOption(
                configuration, MAX_ERROR_RETRY, DEFAULT_MAX_ERROR_RETRY, 0));
        clientConfig.setHttpProtocol(protocol(configuration));

        try {
            COSCredentials sdkCredentials = credentials.sessionToken()
                    .<COSCredentials>map(token -> new BasicSessionCredentials(
                            credentials.accessKey(), credentials.secretKey(), token))
                    .orElseGet(() -> new BasicCOSCredentials(
                            credentials.accessKey(), credentials.secretKey()));
            return new TencentCosStorageClient(
                    configuration.name(), new COSClient(sdkCredentials, clientConfig));
        } catch (RuntimeException exception) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Cannot create Tencent COS client",
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

    private static HttpProtocol protocol(StorageConfiguration configuration) {
        String value = configuration.option(PROTOCOL).orElse("https");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "http" -> HttpProtocol.http;
            case "https" -> HttpProtocol.https;
            default -> throw invalid("Option 'protocol' must be http or https");
        };
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, TYPE, null, false);
    }
}
