package com.jdc.storeweave.provider.sftp;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpStorageCapabilitiesTest {

    private StorageClient client;

    @BeforeEach
    void setUp() {
        client = new SftpStorageClient("sftp-capabilities", "/storage", new InMemorySftpConnectionFactory());
        client.capability(BucketOperations.class).orElseThrow().createBucket("documents");
    }

    @AfterEach
    void tearDown() {
        client.list(ListObjectsRequest.firstPage("documents", "", 1000))
                .items().forEach(item -> client.delete(item.object()));
        client.capability(BucketOperations.class).orElseThrow().deleteBucket("documents");
        client.close();
    }

    @Test
    void advertisesOnlyBucketOperations() {
        assertEquals(Set.of(StorageCapabilityType.BUCKETS), client.capabilities());
        assertTrue(client.capability(BucketOperations.class).isPresent());
        assertTrue(client.capability(PresignOperations.class).isEmpty());
    }

    @Test
    void mapsBucketsAndNestedKeysToDirectories() throws Exception {
        StorageObject object = new StorageObject("documents", "reports/2026/result.txt");
        client.put(new PutObjectRequest(object,
                ObjectContents.fromString("result", StandardCharsets.UTF_8, "text/plain")));

        assertEquals(6, client.stat(object).size());
        assertEquals("reports/2026/result.txt",
                client.list(ListObjectsRequest.firstPage("documents", "reports/", 10))
                        .items().get(0).object().key());
        try (var content = client.get(object)) {
            assertEquals("result", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsRemotePathTraversal() {
        StorageException bucketFailure = assertThrows(StorageException.class,
                () -> client.capability(BucketOperations.class).orElseThrow().createBucket("../outside"));
        IllegalArgumentException keyFailure = assertThrows(IllegalArgumentException.class,
                () -> client.exists(new StorageObject("documents", "safe/../../outside")));

        assertEquals(StorageErrorCode.INVALID_REQUEST, bucketFailure.code());
        assertFalse(bucketFailure.retryable());
        assertTrue(keyFailure.getMessage().contains("must not traverse"));
    }

    @Test
    void requiresKnownHostsForSecureDefault() {
        StorageConfiguration configuration = new StorageConfiguration(
                "secure", "sftp", URI.create("sftp://example.com"), null,
                StorageCredentials.of("user", "password"), Map.of());

        StorageException exception = assertThrows(
                StorageException.class, () -> new SftpStorageProvider().create(configuration));
        assertEquals(StorageErrorCode.INVALID_REQUEST, exception.code());
        assertTrue(exception.getMessage().contains("known-hosts"));
    }

    @Test
    void mapsMissingObjectsToPortableErrors() {
        StorageException exception = assertThrows(StorageException.class,
                () -> client.stat(new StorageObject("documents", "missing.txt")));
        assertEquals(StorageErrorCode.NOT_FOUND, exception.code());
        assertFalse(exception.retryable());
    }
}
