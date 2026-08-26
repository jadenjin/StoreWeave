package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "STOREWEAVE_RUSTFS_INTEGRATION_ENDPOINT",
        matches = "https?://.+")
class RustFsIntegrationIT {

    @Test
    void exercisesARealRustFsServer() throws Exception {
        String bucket = "storeweave-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        StorageClient client = createClient();
        BucketOperations buckets = client.capability(BucketOperations.class).orElseThrow();
        boolean bucketCreated = false;
        try {
            buckets.createBucket(bucket);
            bucketCreated = true;
            StorageObject object = new StorageObject(bucket, "contract/hello.txt");
            client.put(new PutObjectRequest(
                    object,
                    ObjectContents.fromString("hello rustfs", StandardCharsets.UTF_8, "text/plain"),
                    Map.of("provider", "storeweave")));
            assertEquals("storeweave", client.stat(object).userMetadata().get("provider"));
            try (var content = client.get(object)) {
                assertEquals("hello rustfs", new String(content.readAllBytes(), StandardCharsets.UTF_8));
            }

            PresignOperations presign = client.capability(PresignOperations.class).orElseThrow();
            assertTrue(presign.presignGet(new PresignRequest(
                    object, Duration.ofMinutes(5), Map.of())).getRawQuery().contains("X-Amz-Signature="));

            MultipartOperations multipart = client.capability(MultipartOperations.class).orElseThrow();
            StorageObject multipartObject = new StorageObject(bucket, "contract/multipart.bin");
            var upload = multipart.initiate(multipartObject);
            try {
                var part = multipart.uploadPart(upload, 1,
                        ObjectContents.fromBytes("multipart".getBytes(StandardCharsets.UTF_8),
                                "application/octet-stream"));
                assertEquals(List.of(part), multipart.listParts(upload));
                multipart.complete(upload, List.of(part));
            } catch (RuntimeException failure) {
                multipart.abort(upload);
                throw failure;
            }
            assertTrue(client.exists(multipartObject));
        } finally {
            try {
                if (bucketCreated) {
                    String token = null;
                    do {
                        var page = client.list(new ListObjectsRequest(bucket, "", token, 1000));
                        page.items().forEach(item -> client.delete(item.object()));
                        token = page.nextToken();
                    } while (token != null);
                    if (buckets.bucketExists(bucket)) {
                        buckets.deleteBucket(bucket);
                    }
                }
            } finally {
                client.close();
            }
        }
    }

    private static StorageClient createClient() {
        String token = System.getenv("STOREWEAVE_RUSTFS_SESSION_TOKEN");
        StorageCredentials credentials = new StorageCredentials(
                requiredEnvironment("STOREWEAVE_RUSTFS_ACCESS_KEY"),
                requiredEnvironment("STOREWEAVE_RUSTFS_SECRET_KEY"),
                token);
        return new RustFsStorageProvider().create(new StorageConfiguration(
                "rustfs-integration",
                RustFsStorageProvider.TYPE,
                URI.create(requiredEnvironment("STOREWEAVE_RUSTFS_INTEGRATION_ENDPOINT")),
                System.getenv().getOrDefault("STOREWEAVE_RUSTFS_REGION", "us-east-1"),
                credentials,
                Map.of(S3StorageProvider.PATH_STYLE_ACCESS,
                        System.getenv().getOrDefault("STOREWEAVE_RUSTFS_PATH_STYLE", "true"))));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
