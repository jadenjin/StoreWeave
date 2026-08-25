package com.jdc.storeweave.provider.minio;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketInfo;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.LifecycleOperations;
import com.jdc.storeweave.core.capability.LifecycleRule;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.MultipartUpload;
import com.jdc.storeweave.core.capability.NotificationConfiguration;
import com.jdc.storeweave.core.capability.NotificationOperations;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.capability.QuotaOperations;
import com.jdc.storeweave.core.capability.ServerAdminOperations;
import com.jdc.storeweave.core.capability.ServerInfo;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.capability.StorageEventType;
import com.jdc.storeweave.core.capability.UploadedPart;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContent;
import com.jdc.storeweave.core.model.ObjectMetadata;
import com.jdc.storeweave.core.model.PageResult;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.PutObjectResult;
import com.jdc.storeweave.core.model.StorageObject;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketLifecycleArgs;
import io.minio.DeleteBucketNotificationArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.SetBucketNotificationArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import io.minio.messages.EventType;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.Part;
import io.minio.messages.QueueConfiguration;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class MinioStorageClient implements StorageClient,
        BucketOperations,
        PresignOperations,
        MultipartOperations,
        LifecycleOperations,
        NotificationOperations,
        QuotaOperations,
        ServerAdminOperations {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final long UNKNOWN_SIZE_PART_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PRESIGN_SECONDS = 7L * 24 * 60 * 60;
    private static final long KIBIBYTE = 1024L;

    private final String name;
    private final String region;
    private final MinioClient client;
    private final MinioAsyncClient asyncClient;
    private final MinioAdminGateway admin;
    private final OkHttpClient httpClient;

    MinioStorageClient(
            String name,
            String region,
            MinioClient client,
            MinioAsyncClient asyncClient,
            MinioAdminGateway admin,
            OkHttpClient httpClient) {
        this.name = Objects.requireNonNull(name, "name");
        this.region = region;
        this.client = Objects.requireNonNull(client, "client");
        this.asyncClient = Objects.requireNonNull(asyncClient, "asyncClient");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return MinioStorageProvider.TYPE;
    }

    @Override
    public Set<StorageCapabilityType> capabilities() {
        return Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS,
                StorageCapabilityType.LIFECYCLE,
                StorageCapabilityType.NOTIFICATIONS,
                StorageCapabilityType.QUOTA,
                StorageCapabilityType.SERVER_ADMIN);
    }

    @Override
    public PutObjectResult put(PutObjectRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectContent content = request.content();
        if (content.length() >= 0) {
            return putKnownLength(request);
        }
        try (InputStream stream = content.openStream()) {
            var response = client.putObject(PutObjectArgs.builder()
                    .bucket(request.object().bucket())
                    .object(request.object().key())
                    .stream(stream, -1, UNKNOWN_SIZE_PART_BYTES)
                    .contentType(content.contentTypeValue().orElse(DEFAULT_CONTENT_TYPE))
                    .userMetadata(request.metadata())
                    .build());
            return new PutObjectResult(request.object(), response.etag(), response.versionId());
        } catch (Exception exception) {
            throw MinioExceptionMapper.map("Put object " + request.object(), exception);
        }
    }

    private PutObjectResult putKnownLength(PutObjectRequest request) {
        ObjectContent content = request.content();
        String uploadId = null;
        try (InputStream stream = content.openStream()) {
            Multimap<String, String> headers = HashMultimap.create();
            headers.put("Content-Type", content.contentTypeValue().orElse(DEFAULT_CONTENT_TYPE));
            request.metadata().forEach((key, value) -> headers.put("x-amz-meta-" + key, value));
            var initiated = await(asyncClient.createMultipartUploadAsync(
                    request.object().bucket(), region, request.object().key(), headers, null));
            uploadId = initiated.result().uploadId();
            var part = await(asyncClient.uploadPartAsync(
                    request.object().bucket(), region, request.object().key(), stream, content.length(),
                    uploadId, 1, null, null));
            var completed = await(asyncClient.completeMultipartUploadAsync(
                    request.object().bucket(), region, request.object().key(), uploadId,
                    new Part[]{new Part(1, part.etag())}, null, null));
            return new PutObjectResult(request.object(), completed.etag(), completed.versionId());
        } catch (Exception exception) {
            if (uploadId != null) {
                try {
                    await(asyncClient.abortMultipartUploadAsync(
                            request.object().bucket(), region, request.object().key(), uploadId, null, null));
                } catch (Exception cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw MinioExceptionMapper.map("Put object " + request.object(), exception);
        }
    }

    @Override
    public InputStream get(StorageObject object) {
        Objects.requireNonNull(object, "object");
        return execute("Get object " + object, () -> client.getObject(GetObjectArgs.builder()
                .bucket(object.bucket())
                .object(object.key())
                .build()));
    }

    @Override
    public ObjectMetadata stat(StorageObject object) {
        Objects.requireNonNull(object, "object");
        var response = execute("Stat object " + object, () -> client.statObject(StatObjectArgs.builder()
                .bucket(object.bucket())
                .object(object.key())
                .build()));
        return new ObjectMetadata(
                object,
                response.size(),
                response.etag(),
                response.lastModified() == null ? Instant.EPOCH : response.lastModified().toInstant(),
                response.contentType(),
                response.userMetadata());
    }

    @Override
    public boolean exists(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(object.bucket())
                    .object(object.key())
                    .build());
            return true;
        } catch (Exception exception) {
            if (MinioExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw MinioExceptionMapper.map("Check object " + object, exception);
        }
    }

    @Override
    public boolean delete(StorageObject object) {
        Objects.requireNonNull(object, "object");
        if (!exists(object)) {
            return false;
        }
        executeVoid("Delete object " + object, () -> client.removeObject(RemoveObjectArgs.builder()
                .bucket(object.bucket())
                .object(object.key())
                .build()));
        return true;
    }

    @Override
    public PageResult<ObjectMetadata> list(ListObjectsRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            List<ObjectMetadata> items = new ArrayList<>(request.pageSize() + 1);
            Iterable<io.minio.Result<io.minio.messages.Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(request.bucket())
                    .prefix(request.prefix())
                    .startAfter(request.continuationToken())
                    .recursive(true)
                    .maxKeys(Math.min(request.pageSize(), 1000))
                    .build());
            for (var result : results) {
                var item = result.get();
                if (!item.isDir()) {
                    items.add(new ObjectMetadata(
                            new StorageObject(request.bucket(), item.objectName()),
                            item.size(),
                            item.etag(),
                            item.lastModified() == null ? Instant.EPOCH : item.lastModified().toInstant(),
                            null,
                            item.userMetadata()));
                }
                if (items.size() > request.pageSize()) {
                    break;
                }
            }
            String nextToken = items.size() > request.pageSize()
                    ? items.get(request.pageSize() - 1).object().key()
                    : null;
            if (nextToken != null) {
                items.remove(items.size() - 1);
            }
            return new PageResult<>(List.copyOf(items), nextToken);
        } catch (Exception exception) {
            throw MinioExceptionMapper.map("List objects in " + request.bucket(), exception);
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        return execute("Check bucket " + bucketName, () -> client.bucketExists(BucketExistsArgs.builder()
                .bucket(bucketName)
                .build()));
    }

    @Override
    public void createBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        if (bucketExists(bucketName)) {
            return;
        }
        var builder = MakeBucketArgs.builder().bucket(bucketName);
        if (region != null && !region.isBlank()) {
            builder.region(region);
        }
        executeVoid("Create bucket " + bucketName, () -> client.makeBucket(builder.build()));
    }

    @Override
    public void deleteBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        executeVoid("Delete bucket " + bucketName, () -> client.removeBucket(RemoveBucketArgs.builder()
                .bucket(bucketName)
                .build()));
    }

    @Override
    public List<BucketInfo> listBuckets() {
        return execute("List buckets", () -> client.listBuckets().stream()
                .map(bucket -> new BucketInfo(
                        bucket.name(),
                        bucket.creationDate() == null ? null : bucket.creationDate().toInstant()))
                .toList());
    }

    @Override
    public URI presignGet(PresignRequest request) {
        return presign(request, Method.GET);
    }

    @Override
    public URI presignPut(PresignRequest request) {
        return presign(request, Method.PUT);
    }

    private URI presign(PresignRequest request, Method method) {
        Objects.requireNonNull(request, "request");
        long seconds = request.expiresIn().getSeconds();
        if (seconds < 1 || seconds > MAX_PRESIGN_SECONDS) {
            throw invalid("MinIO presigned URL expiration must be between 1 second and 7 days");
        }
        return execute("Presign " + method + " for " + request.object(), () -> URI.create(
                client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(method)
                        .bucket(request.object().bucket())
                        .object(request.object().key())
                        .expiry((int) seconds)
                        .extraHeaders(request.headers())
                        .build())));
    }

    @Override
    public MultipartUpload initiate(StorageObject object) {
        Objects.requireNonNull(object, "object");
        var response = execute("Initiate multipart upload for " + object,
                () -> await(asyncClient.createMultipartUploadAsync(
                        object.bucket(), region, object.key(), null, null)));
        return new MultipartUpload(response.result().uploadId(), object);
    }

    @Override
    public UploadedPart uploadPart(MultipartUpload upload, int partNumber, ObjectContent content) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(content, "content");
        if (partNumber < 1 || partNumber > 10_000) {
            throw invalid("MinIO multipart part number must be between 1 and 10000");
        }
        if (content.length() < 0) {
            throw invalid("Multipart uploads require a known content length");
        }
        try (InputStream stream = content.openStream()) {
            var response = await(asyncClient.uploadPartAsync(
                    upload.object().bucket(), region, upload.object().key(), stream, content.length(),
                    upload.uploadId(), partNumber, null, null));
            return new UploadedPart(partNumber, response.etag(), content.length());
        } catch (Exception exception) {
            throw MinioExceptionMapper.map(
                    "Upload multipart part " + partNumber + " for " + upload.object(), exception);
        }
    }

    @Override
    public void complete(MultipartUpload upload, List<UploadedPart> parts) {
        Objects.requireNonNull(upload, "upload");
        if (parts == null || parts.isEmpty()) {
            throw invalid("At least one uploaded part is required");
        }
        Part[] completed = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> new Part(part.partNumber(), part.eTag()))
                .toArray(Part[]::new);
        execute("Complete multipart upload for " + upload.object(), () -> await(
                asyncClient.completeMultipartUploadAsync(
                        upload.object().bucket(), region, upload.object().key(), upload.uploadId(),
                        completed, null, null)));
    }

    @Override
    public void abort(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        execute("Abort multipart upload for " + upload.object(), () -> await(
                asyncClient.abortMultipartUploadAsync(
                        upload.object().bucket(), region, upload.object().key(), upload.uploadId(), null, null)));
    }

    @Override
    public List<UploadedPart> listParts(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        List<UploadedPart> parts = new ArrayList<>();
        int marker = 0;
        boolean truncated;
        do {
            int currentMarker = marker;
            var response = execute("List multipart parts for " + upload.object(), () -> await(
                    asyncClient.listPartsAsync(
                            upload.object().bucket(), region, upload.object().key(), null,
                            currentMarker, upload.uploadId(), null, null)));
            response.result().partList().forEach(part ->
                    parts.add(new UploadedPart(part.partNumber(), part.etag(), part.partSize())));
            truncated = response.result().isTruncated();
            marker = response.result().nextPartNumberMarker();
        } while (truncated);
        return List.copyOf(parts);
    }

    @Override
    public void putLifecycleRule(String bucket, LifecycleRule rule) {
        String bucketName = requireText(bucket, "bucket");
        Objects.requireNonNull(rule, "rule");
        List<io.minio.messages.LifecycleRule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(existing -> rule.id().equals(existing.id()));
        rules.add(toSdkRule(rule));
        setLifecycleRules(bucketName, rules);
    }

    @Override
    public List<LifecycleRule> listLifecycleRules(String bucket) {
        return sdkLifecycleRules(requireText(bucket, "bucket")).stream()
                .filter(rule -> rule.id() != null
                        && rule.expiration() != null
                        && rule.expiration().days() != null
                        && rule.expiration().days() > 0)
                .map(rule -> new LifecycleRule(
                        rule.id(),
                        rule.filter() == null || rule.filter().prefix() == null ? "" : rule.filter().prefix(),
                        rule.expiration().days(),
                        Status.ENABLED.equals(rule.status())))
                .toList();
    }

    @Override
    public void deleteLifecycleRule(String bucket, String ruleId) {
        String bucketName = requireText(bucket, "bucket");
        String id = requireText(ruleId, "ruleId");
        List<io.minio.messages.LifecycleRule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(rule -> id.equals(rule.id()));
        if (rules.isEmpty()) {
            executeVoid("Delete lifecycle configuration for " + bucketName,
                    () -> client.deleteBucketLifecycle(DeleteBucketLifecycleArgs.builder()
                            .bucket(bucketName)
                            .build()));
        } else {
            setLifecycleRules(bucketName, rules);
        }
    }

    @Override
    public void configureNotifications(String bucket, NotificationConfiguration configuration) {
        String bucketName = requireText(bucket, "bucket");
        Objects.requireNonNull(configuration, "configuration");
        QueueConfiguration queue = new QueueConfiguration();
        queue.setId("storeweave");
        queue.setQueue(configuration.destination().toString());
        queue.setEvents(configuration.events().stream().map(MinioStorageClient::toSdkEvent).toList());
        io.minio.messages.NotificationConfiguration sdkConfiguration =
                new io.minio.messages.NotificationConfiguration();
        sdkConfiguration.setQueueConfigurationList(List.of(queue));
        executeVoid("Configure notifications for " + bucketName,
                () -> client.setBucketNotification(SetBucketNotificationArgs.builder()
                        .bucket(bucketName)
                        .config(sdkConfiguration)
                        .build()));
    }

    @Override
    public void deleteNotifications(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        executeVoid("Delete notifications for " + bucketName,
                () -> client.deleteBucketNotification(DeleteBucketNotificationArgs.builder()
                        .bucket(bucketName)
                        .build()));
    }

    @Override
    public void setQuotaBytes(String bucket, long bytes) {
        String bucketName = requireText(bucket, "bucket");
        if (bytes < KIBIBYTE || bytes % KIBIBYTE != 0) {
            throw invalid("MinIO quota must be a positive multiple of 1024 bytes");
        }
        executeVoid("Set quota for " + bucketName, () -> admin.setQuotaBytes(bucketName, bytes));
    }

    @Override
    public OptionalLong getQuotaBytes(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        long bytes = execute("Get quota for " + bucketName, () -> admin.getQuotaBytes(bucketName));
        return bytes > 0 ? OptionalLong.of(bytes) : OptionalLong.empty();
    }

    @Override
    public void clearQuota(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        executeVoid("Clear quota for " + bucketName, () -> admin.clearQuota(bucketName));
    }

    @Override
    public ServerInfo serverInfo() {
        return execute("Get MinIO server information", admin::serverInfo);
    }

    @Override
    public void close() {
        Exception failure = null;
        try {
            asyncClient.close();
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            client.close();
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        closeHttpClient(httpClient);
        if (failure != null) {
            throw MinioExceptionMapper.map("Close MinIO client", failure);
        }
    }

    static void closeHttpClient(OkHttpClient httpClient) {
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (IOException ignored) {
                // A closed cache does not prevent releasing the remaining client resources.
            }
        }
    }

    private List<io.minio.messages.LifecycleRule> sdkLifecycleRules(String bucket) {
        try {
            LifecycleConfiguration configuration = client.getBucketLifecycle(GetBucketLifecycleArgs.builder()
                    .bucket(bucket)
                    .build());
            return configuration == null || configuration.rules() == null
                    ? List.of()
                    : List.copyOf(configuration.rules());
        } catch (Exception exception) {
            if (MinioExceptionMapper.isMissingLifecycle(exception)) {
                return List.of();
            }
            throw MinioExceptionMapper.map("Get lifecycle configuration for " + bucket, exception);
        }
    }

    private void setLifecycleRules(String bucket, List<io.minio.messages.LifecycleRule> rules) {
        executeVoid("Set lifecycle configuration for " + bucket,
                () -> client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                        .bucket(bucket)
                        .config(new LifecycleConfiguration(rules))
                        .build()));
    }

    private static io.minio.messages.LifecycleRule toSdkRule(LifecycleRule rule) {
        return new io.minio.messages.LifecycleRule(
                rule.enabled() ? Status.ENABLED : Status.DISABLED,
                null,
                new Expiration((ZonedDateTime) null, rule.expireAfterDays(), null),
                new RuleFilter(rule.prefix()),
                rule.id(),
                null,
                null,
                null);
    }

    private static EventType toSdkEvent(StorageEventType event) {
        return switch (event) {
            case OBJECT_CREATED -> EventType.OBJECT_CREATED_ANY;
            case OBJECT_REMOVED -> EventType.OBJECT_REMOVED_ANY;
        };
    }

    private <T> T execute(String operation, CheckedSupplier<T> action) {
        try {
            return action.get();
        } catch (Exception exception) {
            throw MinioExceptionMapper.map(operation, exception);
        }
    }

    private void executeVoid(String operation, CheckedRunnable action) {
        execute(operation, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, providerType(), null, false);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
