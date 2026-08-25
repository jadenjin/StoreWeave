package com.jdc.storeweave.testkit;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reusable behavior contract that every provider should pass. */
public abstract class AbstractStorageClientContract {

    private static final String BUCKET = "contract-bucket";
    private StorageClient client;

    protected abstract StorageClient createClient();

    @BeforeEach
    void setUpStorageContract() {
        client = createClient();
        client.capability(BucketOperations.class).orElseThrow().createBucket(BUCKET);
    }

    @AfterEach
    void tearDownStorageContract() {
        if (client != null) {
            try {
                String token = null;
                do {
                    var page = client.list(new ListObjectsRequest(BUCKET, "", token, 1000));
                    page.items().forEach(item -> client.delete(item.object()));
                    token = page.nextToken();
                } while (token != null);
                client.capability(BucketOperations.class).orElseThrow().deleteBucket(BUCKET);
            } finally {
                client.close();
            }
        }
    }

    @Test
    void providerHonorsBasicObjectLifecycle() throws Exception {
        StorageObject object = new StorageObject(BUCKET, "docs/hello.txt");
        client.put(new PutObjectRequest(
                object,
                ObjectContents.fromString("storeweave", StandardCharsets.UTF_8, "text/plain")));

        assertTrue(client.exists(object));
        try (var content = client.get(object)) {
            assertEquals("storeweave", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(10, client.stat(object).size());
        assertEquals(1, client.list(ListObjectsRequest.firstPage(BUCKET, "docs/", 10)).items().size());
        assertTrue(client.delete(object));
        assertFalse(client.exists(object));
    }

    @Test
    void providerHonorsContinuationTokenPagination() {
        for (int index = 1; index <= 3; index++) {
            StorageObject object = new StorageObject(BUCKET, "page/file-" + index + ".txt");
            client.put(new PutObjectRequest(
                    object,
                    ObjectContents.fromString("item-" + index, StandardCharsets.UTF_8, "text/plain")));
        }

        var first = client.list(ListObjectsRequest.firstPage(BUCKET, "page/", 2));
        assertEquals(2, first.items().size());
        assertTrue(first.hasNext());

        var second = client.list(new ListObjectsRequest(BUCKET, "page/", first.nextToken(), 2));
        assertEquals(1, second.items().size());
        assertFalse(second.hasNext());
    }

    @Test
    void providerOverwritesExistingObject() throws Exception {
        StorageObject object = new StorageObject(BUCKET, "overwrite/item.txt");
        client.put(new PutObjectRequest(
                object,
                ObjectContents.fromString("old", StandardCharsets.UTF_8, "text/plain")));
        client.put(new PutObjectRequest(
                object,
                ObjectContents.fromString("new-content", StandardCharsets.UTF_8, "text/plain")));

        try (var content = client.get(object)) {
            assertEquals("new-content", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(11, client.stat(object).size());
    }
}
