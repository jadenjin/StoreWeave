package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.StorageObject;
import com.jdc.storeweave.testkit.s3.S3CompatibleTestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RustFsStorageCapabilitiesTest {

    private static final String BUCKET = "rustfs-capability-bucket";

    private final S3CompatibleTestServer server = new S3CompatibleTestServer();
    private StorageClient client;

    @BeforeEach
    void setUp() {
        client = createClient(server.endpoint());
        client.capability(BucketOperations.class).orElseThrow().createBucket(BUCKET);
    }

    @AfterEach
    void tearDown() {
        try {
            client.list(ListObjectsRequest.firstPage(BUCKET, "", 1000))
                    .items().forEach(item -> client.delete(item.object()));
            client.capability(BucketOperations.class).orElseThrow().deleteBucket(BUCKET);
        } finally {
            client.close();
        }
    }

    @AfterAll
    void stopServer() {
        server.close();
    }

    @Test
    void advertisesTheTestedRustFsCapabilities() {
        assertEquals(RustFsStorageProvider.TYPE, client.providerType());
        assertEquals(Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS), client.capabilities());
        assertTrue(client.capability(PresignOperations.class).isPresent());
        assertTrue(client.capability(MultipartOperations.class).isPresent());
    }

    @Test
    void enablesPathStylePresigningByDefault() {
        java.net.URI endpoint = java.net.URI.create(
                "http://localhost:" + server.endpoint().getPort());
        try (StorageClient localhostClient = createClient(endpoint)) {
            PresignOperations presign = localhostClient.capability(PresignOperations.class).orElseThrow();
            StorageObject object = new StorageObject(BUCKET, "signed/report.txt");

            var uri = presign.presignGet(new PresignRequest(object, Duration.ofMinutes(10), Map.of()));

            assertEquals("localhost", uri.getHost());
            assertEquals("/" + BUCKET + "/signed/report.txt", uri.getPath());
            assertTrue(uri.getRawQuery().contains("X-Amz-Signature="));
        }
    }

    @Test
    void requiresEndpointAndStaticCredentials() {
        StorageException endpointFailure = assertThrows(StorageException.class,
                () -> new RustFsStorageProvider().create(new StorageConfiguration(
                        "rustfs", "rustfs", null, null,
                        StorageCredentials.of("access", "secret"), Map.of())));
        StorageException credentialFailure = assertThrows(StorageException.class,
                () -> new RustFsStorageProvider().create(new StorageConfiguration(
                        "rustfs", "rustfs", server.endpoint(), null, null, Map.of())));

        assertEquals(StorageErrorCode.INVALID_REQUEST, endpointFailure.code());
        assertEquals(RustFsStorageProvider.TYPE, endpointFailure.providerType().orElseThrow());
        assertEquals(StorageErrorCode.INVALID_REQUEST, credentialFailure.code());
        assertEquals(RustFsStorageProvider.TYPE, credentialFailure.providerType().orElseThrow());
        assertFalse(endpointFailure.retryable());
        assertFalse(credentialFailure.retryable());
    }

    @Test
    void preservesRustFsTypeWhenMappingProtocolErrors() {
        StorageException exception = assertThrows(StorageException.class,
                () -> client.stat(new StorageObject(BUCKET, "missing.txt")));

        assertEquals(StorageErrorCode.NOT_FOUND, exception.code());
        assertEquals(RustFsStorageProvider.TYPE, exception.providerType().orElseThrow());
        assertFalse(exception.retryable());
    }

    private StorageClient createClient(java.net.URI endpoint) {
        return new RustFsStorageProvider().create(new StorageConfiguration(
                "rustfs-capabilities",
                RustFsStorageProvider.TYPE,
                endpoint,
                null,
                StorageCredentials.of("test-access-key", "test-secret-key"),
                Map.of()));
    }
}
