package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.admin.MinioAdminClient;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import okhttp3.OkHttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

public final class MinioStorageProvider implements StorageProvider {

    public static final String TYPE = "minio";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String READ_TIMEOUT_MS = "read-timeout-ms";
    public static final String WRITE_TIMEOUT_MS = "write-timeout-ms";

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_READ_TIMEOUT_MS = 300_000;
    private static final long DEFAULT_WRITE_TIMEOUT_MS = 300_000;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("MinIO provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("MinIO endpoint is required"));
        validateEndpoint(endpoint);
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("MinIO access key and secret key are required"));
        Provider credentialsProvider = new StaticProvider(
                credentials.accessKey(),
                credentials.secretKey(),
                credentials.sessionToken().orElse(null));

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(positiveOption(
                        configuration, CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS)))
                .readTimeout(Duration.ofMillis(positiveOption(
                        configuration, READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS)))
                .writeTimeout(Duration.ofMillis(positiveOption(
                        configuration, WRITE_TIMEOUT_MS, DEFAULT_WRITE_TIMEOUT_MS)))
                .build();

        MinioClient client = null;
        MinioAsyncClient asyncClient = null;
        try {
            var clientBuilder = MinioClient.builder()
                    .endpoint(endpoint.toString())
                    .credentialsProvider(credentialsProvider)
                    .httpClient(httpClient, false);
            var asyncBuilder = MinioAsyncClient.builder()
                    .endpoint(endpoint.toString())
                    .credentialsProvider(credentialsProvider)
                    .httpClient(httpClient, false);
            var adminBuilder = MinioAdminClient.builder()
                    .endpoint(endpoint.toString())
                    .credentialsProvider(credentialsProvider)
                    .httpClient(httpClient);
            configuration.regionValue().ifPresent(region -> {
                clientBuilder.region(region);
                asyncBuilder.region(region);
                adminBuilder.region(region);
            });

            client = clientBuilder.build();
            asyncClient = asyncBuilder.build();
            MinioAdminClient adminClient = adminBuilder.build();
            return new MinioStorageClient(
                    configuration.name(),
                    configuration.region(),
                    client,
                    asyncClient,
                    new SdkMinioAdminGateway(adminClient),
                    httpClient);
        } catch (RuntimeException exception) {
            closeQuietly(asyncClient);
            closeQuietly(client);
            MinioStorageClient.closeHttpClient(httpClient);
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Cannot create MinIO client",
                    TYPE,
                    exception,
                    false);
        }
    }

    private static long positiveOption(
            StorageConfiguration configuration,
            String name,
            long defaultValue) {
        return configuration.option(name).map(value -> {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 1) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw invalid("Option '" + name + "' must be a positive integer");
            }
        }).orElse(defaultValue);
    }

    private static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw invalid("MinIO endpoint must be an absolute HTTP(S) URI");
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Preserve the original client creation failure.
            }
        }
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, TYPE, null, false);
    }
}
