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
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageCapabilitiesTest {

    private static final String BUCKET = "capability-bucket";

    private final S3CompatibleTestServer server = new S3CompatibleTestServer();
    private StorageClient client;

    @BeforeEach
    void setUp() {
        client = new S3StorageProvider().create(new StorageConfiguration(
                "s3-capabilities",
                S3StorageProvider.TYPE,
                server.endpoint(),
                "us-east-1",
                StorageCredentials.of("test-access-key", "test-secret-key"),
                Map.of(S3StorageProvider.PATH_STYLE_ACCESS, "true")));
        client.capability(BucketOperations.class).orElseThrow().createBucket(BUCKET);
    }

    @AfterEach
    void tearDown() {
        try {
            var page = client.list(com.jdc.storeweave.core.model.ListObjectsRequest.firstPage(BUCKET, "", 1000));
            page.items().forEach(item -> client.delete(item.object()));
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
    void advertisesOnlyImplementedCapabilities() {
        assertEquals(
                java.util.Set.of(
                        StorageCapabilityType.BUCKETS,
                        StorageCapabilityType.PRESIGNED_URLS,
                        StorageCapabilityType.MULTIPART_UPLOADS),
                client.capabilities());
    }

    @Test
    void createsSignedGetAndPutUrls() {
        PresignOperations presign = client.capability(PresignOperations.class).orElseThrow();
        PresignRequest request = new PresignRequest(
                new StorageObject(BUCKET, "signed/report.txt"),
                Duration.ofMinutes(10),
                Map.of("Content-Type", "text/plain"));

        var get = presign.presignGet(request);
        var put = presign.presignPut(request);

        assertEquals(server.endpoint().getHost(), get.getHost());
        assertEquals(server.endpoint().getPort(), get.getPort());
        assertTrue(get.getRawQuery().contains("X-Amz-Signature="));
        assertTrue(put.getRawQuery().contains("X-Amz-Signature="));
        assertTrue(put.getRawQuery().contains("X-Amz-SignedHeaders="));
    }

    @Test
    void completesAndAbortsMultipartUploads() throws Exception {
        MultipartOperations multipart = client.capability(MultipartOperations.class).orElseThrow();
        StorageObject object = new StorageObject(BUCKET, "multipart/archive.bin");
        var upload = multipart.initiate(object);
        var first = multipart.uploadPart(
                upload,
                1,
                ObjectContents.fromBytes("hello ".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        var second = multipart.uploadPart(
                upload,
                2,
                ObjectContents.fromBytes("world".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));

        assertEquals(List.of(first, second), multipart.listParts(upload));
        multipart.complete(upload, List.of(second, first));

        try (var content = client.get(object)) {
            assertEquals("hello world", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }

        var aborted = multipart.initiate(new StorageObject(BUCKET, "multipart/aborted.bin"));
        multipart.abort(aborted);
        assertFalse(client.exists(aborted.object()));
    }

    @Test
    void mapsMissingObjectsToThePortableErrorModel() {
        StorageException exception = assertThrows(
                StorageException.class,
                () -> client.stat(new StorageObject(BUCKET, "missing.txt")));

        assertEquals(StorageErrorCode.NOT_FOUND, exception.code());
        assertFalse(exception.retryable());
    }
}
