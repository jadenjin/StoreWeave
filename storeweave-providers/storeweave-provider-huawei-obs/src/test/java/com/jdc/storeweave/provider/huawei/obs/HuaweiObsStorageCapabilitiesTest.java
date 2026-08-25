package com.jdc.storeweave.provider.huawei.obs;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.LifecycleOperations;
import com.jdc.storeweave.core.capability.LifecycleRule;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuaweiObsStorageCapabilitiesTest {

    private static final String BUCKET = "huawei-capability-bucket";
    private StorageClient client;

    @BeforeEach
    void setUp() {
        client = new HuaweiObsStorageClient("huawei-capabilities", "test-region", new InMemoryHuaweiObs());
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

    @Test
    void advertisesOnlyImplementedCapabilities() {
        assertEquals(Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS,
                StorageCapabilityType.LIFECYCLE), client.capabilities());
    }

    @Test
    void preservesMetadataAndCreatesPresignedUrls() {
        StorageObject object = new StorageObject(BUCKET, "reports/item.txt");
        client.put(new PutObjectRequest(
                object,
                ObjectContents.fromString("content", StandardCharsets.UTF_8, "text/plain"),
                Map.of("owner", "storeweave")));
        assertEquals("text/plain", client.stat(object).contentType());
        assertEquals("storeweave", client.stat(object).userMetadata().get("owner"));

        PresignOperations presign = client.capability(PresignOperations.class).orElseThrow();
        PresignRequest request = new PresignRequest(object, Duration.ofMinutes(10), Map.of());
        assertTrue(presign.presignGet(request).getRawQuery().contains("q-signature="));
        assertTrue(presign.presignPut(request).getRawQuery().contains("q-signature="));
    }

    @Test
    void completesAndAbortsMultipartUploads() throws Exception {
        MultipartOperations multipart = client.capability(MultipartOperations.class).orElseThrow();
        StorageObject object = new StorageObject(BUCKET, "multipart/archive.bin");
        var upload = multipart.initiate(object);
        var first = multipart.uploadPart(upload, 1,
                ObjectContents.fromBytes("hello ".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        var second = multipart.uploadPart(upload, 2,
                ObjectContents.fromBytes("world".getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
        assertEquals(List.of(first, second), multipart.listParts(upload));
        multipart.complete(upload, List.of(second, first));
        try (var stream = client.get(object)) {
            assertEquals("hello world", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        var aborted = multipart.initiate(new StorageObject(BUCKET, "multipart/aborted.bin"));
        multipart.abort(aborted);
        assertFalse(client.exists(aborted.object()));
    }

    @Test
    void managesLifecycleRulesById() {
        LifecycleOperations lifecycle = client.capability(LifecycleOperations.class).orElseThrow();
        LifecycleRule first = new LifecycleRule("archive", "logs/", 30, true);
        LifecycleRule replacement = new LifecycleRule("archive", "archive/", 90, false);
        lifecycle.putLifecycleRule(BUCKET, first);
        assertEquals(List.of(first), lifecycle.listLifecycleRules(BUCKET));
        lifecycle.putLifecycleRule(BUCKET, replacement);
        assertEquals(List.of(replacement), lifecycle.listLifecycleRules(BUCKET));
        lifecycle.deleteLifecycleRule(BUCKET, replacement.id());
        assertTrue(lifecycle.listLifecycleRules(BUCKET).isEmpty());
    }

    @Test
    void mapsMissingObjectsToPortableErrors() {
        StorageException exception = assertThrows(
                StorageException.class,
                () -> client.stat(new StorageObject(BUCKET, "missing.txt")));
        assertEquals(StorageErrorCode.NOT_FOUND, exception.code());
        assertFalse(exception.retryable());
    }
}
