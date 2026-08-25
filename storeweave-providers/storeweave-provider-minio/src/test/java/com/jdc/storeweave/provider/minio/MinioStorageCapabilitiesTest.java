package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.LifecycleOperations;
import com.jdc.storeweave.core.capability.LifecycleRule;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.NotificationConfiguration;
import com.jdc.storeweave.core.capability.NotificationOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.capability.QuotaOperations;
import com.jdc.storeweave.core.capability.ServerAdminOperations;
import com.jdc.storeweave.core.capability.ServerInfo;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.capability.StorageEventType;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContents;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.StorageObject;
import com.jdc.storeweave.testkit.s3.S3CompatibleTestServer;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.credentials.StaticProvider;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioStorageCapabilitiesTest {

    private static final String BUCKET = "minio-capability-bucket";

    private final S3CompatibleTestServer server = new S3CompatibleTestServer();
    private final FakeAdminGateway admin = new FakeAdminGateway();
    private StorageClient client;

    @BeforeEach
    void setUp() {
        OkHttpClient httpClient = new OkHttpClient();
        StaticProvider credentials = new StaticProvider("test-access-key", "test-secret-key", null);
        MinioClient minioClient = MinioClient.builder()
                .endpoint(server.endpoint().toString())
                .region("us-east-1")
                .credentialsProvider(credentials)
                .httpClient(httpClient, false)
                .build();
        MinioAsyncClient asyncClient = MinioAsyncClient.builder()
                .endpoint(server.endpoint().toString())
                .region("us-east-1")
                .credentialsProvider(credentials)
                .httpClient(httpClient, false)
                .build();
        client = new MinioStorageClient(
                "minio-capabilities", "us-east-1", minioClient, asyncClient, admin, httpClient);
        client.capability(BucketOperations.class).orElseThrow().createBucket(BUCKET);
    }

    @AfterEach
    void tearDown() {
        try {
            var page = client.list(ListObjectsRequest.firstPage(BUCKET, "", 1000));
            page.items().forEach(item -> client.delete(item.object()));
            client.capability(BucketOperations.class).orElseThrow().deleteBucket(BUCKET);
        } finally {
            client.close();
            admin.reset();
        }
    }

    @AfterAll
    void stopServer() {
        server.close();
    }

    @Test
    void advertisesOnlyImplementedCapabilities() {
        assertEquals(Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS,
                StorageCapabilityType.LIFECYCLE,
                StorageCapabilityType.NOTIFICATIONS,
                StorageCapabilityType.QUOTA,
                StorageCapabilityType.SERVER_ADMIN), client.capabilities());
    }

    @Test
    void createsSignedGetAndPutUrls() {
        PresignOperations presign = client.capability(PresignOperations.class).orElseThrow();
        PresignRequest request = new PresignRequest(
                new StorageObject(BUCKET, "signed/report.txt"),
                Duration.ofMinutes(10),
                Map.of("Content-Type", "text/plain"));

        URI get = presign.presignGet(request);
        URI put = presign.presignPut(request);

        assertEquals(server.endpoint().getHost(), get.getHost());
        assertEquals(server.endpoint().getPort(), get.getPort());
        assertTrue(get.getRawQuery().contains("X-Amz-Signature="));
        assertTrue(put.getRawQuery().contains("X-Amz-Signature="));
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

        try (var content = client.get(object)) {
            assertEquals("hello world", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }

        var aborted = multipart.initiate(new StorageObject(BUCKET, "multipart/aborted.bin"));
        multipart.abort(aborted);
        assertFalse(client.exists(aborted.object()));
    }

    @Test
    void preservesContentTypeAndUserMetadataForKnownLengthUploads() {
        StorageObject object = new StorageObject(BUCKET, "metadata/report.txt");
        client.put(new PutObjectRequest(
                object,
                ObjectContents.fromString("report", StandardCharsets.UTF_8, "text/plain"),
                Map.of("owner", "storeweave")));

        var metadata = client.stat(object);
        assertEquals("text/plain", metadata.contentType());
        assertEquals("storeweave", metadata.userMetadata().get("owner"));
    }

    @Test
    void managesPortableLifecycleRules() {
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
    void configuresMinioQueueNotifications() {
        NotificationOperations notifications = client.capability(NotificationOperations.class).orElseThrow();
        notifications.configureNotifications(BUCKET, new NotificationConfiguration(
                URI.create("arn:minio:sqs:us-east-1:primary:webhook"),
                Set.of(StorageEventType.OBJECT_CREATED, StorageEventType.OBJECT_REMOVED)));

        String xml = server.notificationConfiguration(BUCKET);
        assertTrue(xml.contains("arn:minio:sqs:us-east-1:primary:webhook"));
        assertTrue(xml.contains("s3:ObjectCreated:*"));
        assertTrue(xml.contains("s3:ObjectRemoved:*"));

        notifications.deleteNotifications(BUCKET);
        assertNull(server.notificationConfiguration(BUCKET));
    }

    @Test
    void exposesQuotaAndServerAdministrationThroughCapabilities() {
        QuotaOperations quota = client.capability(QuotaOperations.class).orElseThrow();
        ServerAdminOperations serverAdmin = client.capability(ServerAdminOperations.class).orElseThrow();

        quota.setQuotaBytes(BUCKET, 2048);
        assertEquals(OptionalLong.of(2048), quota.getQuotaBytes(BUCKET));
        quota.clearQuota(BUCKET);
        assertEquals(OptionalLong.empty(), quota.getQuotaBytes(BUCKET));
        assertEquals(new ServerInfo("RELEASE.test", Map.of("mode", "distributed")), serverAdmin.serverInfo());

        StorageException invalid = assertThrows(StorageException.class, () -> quota.setQuotaBytes(BUCKET, 1000));
        assertEquals(StorageErrorCode.INVALID_REQUEST, invalid.code());
    }

    @Test
    void mapsMissingObjectsToThePortableErrorModel() {
        StorageException exception = assertThrows(
                StorageException.class,
                () -> client.stat(new StorageObject(BUCKET, "missing.txt")));

        assertEquals(StorageErrorCode.NOT_FOUND, exception.code());
        assertFalse(exception.retryable());
    }

    private static final class FakeAdminGateway implements MinioAdminGateway {

        private long quota;

        @Override
        public void setQuotaBytes(String bucket, long bytes) {
            quota = bytes;
        }

        @Override
        public long getQuotaBytes(String bucket) {
            return quota;
        }

        @Override
        public void clearQuota(String bucket) {
            quota = 0;
        }

        @Override
        public ServerInfo serverInfo() {
            return new ServerInfo("RELEASE.test", Map.of("mode", "distributed"));
        }

        void reset() {
            quota = 0;
        }
    }
}
