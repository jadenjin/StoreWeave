package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Locale;

public final class S3StorageProvider implements StorageProvider {

    public static final String TYPE = "s3";
    public static final String PATH_STYLE_ACCESS = "path-style-access";
    private static final String DEFAULT_REGION = "us-east-1";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid(TYPE, "S3 provider cannot create storage type: " + configuration.type());
        }
        return createClient(configuration, TYPE, false);
    }

    static StorageClient createClient(
            StorageConfiguration configuration,
            String providerType,
            boolean defaultPathStyleAccess) {
        Region region = Region.of(configuration.regionValue().orElse(DEFAULT_REGION));
        AwsCredentialsProvider credentialsProvider = credentialsProvider(configuration);
        boolean pathStyleAccess = booleanOption(
                configuration, PATH_STYLE_ACCESS, defaultPathStyleAccess, providerType);
        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(serviceConfiguration);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(serviceConfiguration);

        URI endpoint = configuration.endpoint();
        if (endpoint != null) {
            validateEndpoint(endpoint, providerType);
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }

        S3Client client = null;
        try {
            client = clientBuilder.build();
            S3Presigner presigner = presignerBuilder.build();
            return new S3StorageClient(
                    configuration.name(),
                    providerType,
                    region,
                    endpoint != null,
                    client,
                    presigner);
        } catch (RuntimeException exception) {
            if (client != null) {
                client.close();
            }
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Cannot create " + providerType + " client",
                    providerType,
                    exception,
                    false);
        }
    }

    private static AwsCredentialsProvider credentialsProvider(StorageConfiguration configuration) {
        return configuration.credentialsValue()
                .<AwsCredentialsProvider>map(S3StorageProvider::staticCredentials)
                .orElseGet(() -> DefaultCredentialsProvider.builder().build());
    }

    private static AwsCredentialsProvider staticCredentials(StorageCredentials credentials) {
        return credentials.sessionToken()
                .<AwsCredentialsProvider>map(token -> StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(
                                credentials.accessKey(),
                                credentials.secretKey(),
                                token)))
                .orElseGet(() -> StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey())));
    }

    private static boolean booleanOption(
            StorageConfiguration configuration,
            String name,
            boolean defaultValue,
            String providerType) {
        return configuration.option(name)
                .map(value -> switch (value.toLowerCase(Locale.ROOT)) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> throw invalid(
                            providerType, "Option '" + name + "' must be true or false");
                })
                .orElse(defaultValue);
    }

    static void validateEndpoint(URI endpoint, String providerType) {
        String scheme = endpoint.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null) {
            throw invalid(providerType, "S3 endpoint must be an absolute HTTP(S) URI");
        }
    }

    static StorageException invalid(String providerType, String message) {
        return new StorageException(
                StorageErrorCode.INVALID_REQUEST, message, providerType, null, false);
    }
}
