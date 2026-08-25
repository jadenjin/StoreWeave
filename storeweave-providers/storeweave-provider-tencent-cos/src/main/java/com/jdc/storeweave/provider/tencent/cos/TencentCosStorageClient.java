package com.jdc.storeweave.provider.tencent.cos;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketInfo;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.LifecycleOperations;
import com.jdc.storeweave.core.capability.MultipartOperations;
import com.jdc.storeweave.core.capability.MultipartUpload;
import com.jdc.storeweave.core.capability.PresignOperations;
import com.jdc.storeweave.core.capability.PresignRequest;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.capability.UploadedPart;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectContent;
import com.jdc.storeweave.core.model.PageResult;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.PutObjectResult;
import com.jdc.storeweave.core.model.StorageObject;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.AbortMultipartUploadRequest;
import com.qcloud.cos.model.BucketLifecycleConfiguration;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.InitiateMultipartUploadRequest;
import com.qcloud.cos.model.ListPartsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PartETag;
import com.qcloud.cos.model.UploadPartRequest;
import com.qcloud.cos.model.lifecycle.LifecycleFilter;
import com.qcloud.cos.model.lifecycle.LifecyclePrefixPredicate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class TencentCosStorageClient
        implements StorageClient, BucketOperations, PresignOperations, MultipartOperations, LifecycleOperations {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final String name;
    private final COSClient client;

    TencentCosStorageClient(String name, COSClient client) {
        this.name = Objects.requireNonNull(name, "name");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return TencentCosStorageProvider.TYPE;
    }

    @Override
    public Set<StorageCapabilityType> capabilities() {
        return Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS,
                StorageCapabilityType.LIFECYCLE);
    }

    @Override
    public PutObjectResult put(PutObjectRequest request) {
        Objects.requireNonNull(request, "request");
        ObjectMetadata metadata = new ObjectMetadata();
        if (request.content().length() >= 0) {
            metadata.setContentLength(request.content().length());
        }
        metadata.setContentType(request.content().contentTypeValue().orElse(DEFAULT_CONTENT_TYPE));
        metadata.setUserMetadata(request.metadata());
        try (InputStream stream = request.content().openStream()) {
            var result = client.putObject(
                    request.object().bucket(), request.object().key(), stream, metadata);
            return new PutObjectResult(request.object(), result.getETag(), result.getVersionId());
        } catch (IOException exception) {
            throw contentFailure("Cannot read content for " + request.object(), exception);
        } catch (RuntimeException exception) {
            throw TencentCosExceptionMapper.map("Put object " + request.object(), exception);
        }
    }

    @Override
    public InputStream get(StorageObject object) {
        Objects.requireNonNull(object, "object");
        COSObject response = execute("Get object " + object,
                () -> client.getObject(object.bucket(), object.key()));
        return new FilterInputStream(response.getObjectContent()) {
            @Override
            public void close() throws IOException {
                response.close();
            }
        };
    }

    @Override
    public com.jdc.storeweave.core.model.ObjectMetadata stat(StorageObject object) {
        Objects.requireNonNull(object, "object");
        ObjectMetadata metadata = execute("Stat object " + object,
                () -> client.getObjectMetadata(object.bucket(), object.key()));
        return toCoreMetadata(object, metadata);
    }

    @Override
    public boolean exists(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            return client.doesObjectExist(object.bucket(), object.key());
        } catch (RuntimeException exception) {
            if (TencentCosExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw TencentCosExceptionMapper.map("Check object " + object, exception);
        }
    }

    @Override
    public boolean delete(StorageObject object) {
        Objects.requireNonNull(object, "object");
        if (!exists(object)) {
            return false;
        }
        execute("Delete object " + object,
                () -> client.deleteObject(object.bucket(), object.key()));
        return true;
    }

    @Override
    public PageResult<com.jdc.storeweave.core.model.ObjectMetadata> list(ListObjectsRequest request) {
        Objects.requireNonNull(request, "request");
        com.qcloud.cos.model.ListObjectsRequest sdkRequest = new com.qcloud.cos.model.ListObjectsRequest(
                request.bucket(), request.prefix(), request.continuationToken(), null, request.pageSize());
        var listing = execute("List objects in " + request.bucket(), () -> client.listObjects(sdkRequest));
        List<com.jdc.storeweave.core.model.ObjectMetadata> items = listing.getObjectSummaries().stream()
                .map(summary -> new com.jdc.storeweave.core.model.ObjectMetadata(
                        new StorageObject(request.bucket(), summary.getKey()),
                        summary.getSize(),
                        summary.getETag(),
                        summary.getLastModified() == null
                                ? Instant.EPOCH
                                : summary.getLastModified().toInstant(),
                        null,
                        Map.of()))
                .toList();
        return new PageResult<>(items, listing.isTruncated() ? listing.getNextMarker() : null);
    }

    @Override
    public boolean bucketExists(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        try {
            return client.doesBucketExist(bucketName);
        } catch (RuntimeException exception) {
            if (TencentCosExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw TencentCosExceptionMapper.map("Check bucket " + bucketName, exception);
        }
    }

    @Override
    public void createBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        if (!bucketExists(bucketName)) {
            execute("Create bucket " + bucketName, () -> client.createBucket(bucketName));
        }
    }

    @Override
    public void deleteBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        execute("Delete bucket " + bucketName, () -> client.deleteBucket(bucketName));
    }

    @Override
    public List<BucketInfo> listBuckets() {
        return execute("List buckets", () -> client.listBuckets().stream()
                .map(bucket -> new BucketInfo(
                        bucket.getName(),
                        bucket.getCreationDate() == null ? null : bucket.getCreationDate().toInstant()))
                .toList());
    }

    @Override
    public URI presignGet(PresignRequest request) {
        return presign(request, HttpMethodName.GET);
    }

    @Override
    public URI presignPut(PresignRequest request) {
        return presign(request, HttpMethodName.PUT);
    }

    private URI presign(PresignRequest request, HttpMethodName method) {
        Objects.requireNonNull(request, "request");
        long expiresAt;
        try {
            expiresAt = Math.addExact(System.currentTimeMillis(), request.expiresIn().toMillis());
        } catch (ArithmeticException exception) {
            throw invalid("Tencent COS presigned URL expiration is too large");
        }
        return execute("Presign " + method + " for " + request.object(), () -> URI.create(
                client.generatePresignedUrl(
                        request.object().bucket(),
                        request.object().key(),
                        new Date(expiresAt),
                        method,
                        request.headers(),
                        Map.of()).toString()));
    }

    @Override
    public MultipartUpload initiate(StorageObject object) {
        Objects.requireNonNull(object, "object");
        var result = execute("Initiate multipart upload for " + object,
                () -> client.initiateMultipartUpload(
                        new InitiateMultipartUploadRequest(object.bucket(), object.key())));
        return new MultipartUpload(result.getUploadId(), object);
    }

    @Override
    public UploadedPart uploadPart(MultipartUpload upload, int partNumber, ObjectContent content) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(content, "content");
        if (partNumber < 1 || partNumber > 10_000) {
            throw invalid("Tencent COS multipart part number must be between 1 and 10000");
        }
        if (content.length() < 0) {
            throw invalid("Multipart uploads require a known content length");
        }
        UploadPartRequest request = new UploadPartRequest()
                .withBucketName(upload.object().bucket())
                .withKey(upload.object().key())
                .withUploadId(upload.uploadId())
                .withPartNumber(partNumber)
                .withPartSize(content.length());
        try (InputStream stream = content.openStream()) {
            request.setInputStream(stream);
            var result = client.uploadPart(request);
            return new UploadedPart(result.getPartNumber(), result.getETag(), content.length());
        } catch (IOException exception) {
            throw contentFailure("Cannot read multipart content for " + upload.object(), exception);
        } catch (RuntimeException exception) {
            throw TencentCosExceptionMapper.map(
                    "Upload multipart part " + partNumber + " for " + upload.object(), exception);
        }
    }

    @Override
    public void complete(MultipartUpload upload, List<UploadedPart> parts) {
        Objects.requireNonNull(upload, "upload");
        if (parts == null || parts.isEmpty()) {
            throw invalid("At least one uploaded part is required");
        }
        List<PartETag> partETags = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> new PartETag(part.partNumber(), part.eTag()))
                .toList();
        execute("Complete multipart upload for " + upload.object(),
                () -> client.completeMultipartUpload(new CompleteMultipartUploadRequest(
                        upload.object().bucket(), upload.object().key(), upload.uploadId(), partETags)));
    }

    @Override
    public void abort(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        execute("Abort multipart upload for " + upload.object(),
                () -> client.abortMultipartUpload(new AbortMultipartUploadRequest(
                        upload.object().bucket(), upload.object().key(), upload.uploadId())));
    }

    @Override
    public List<UploadedPart> listParts(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        List<UploadedPart> parts = new ArrayList<>();
        Integer marker = null;
        boolean truncated;
        do {
            ListPartsRequest request = new ListPartsRequest(
                    upload.object().bucket(), upload.object().key(), upload.uploadId());
            request.setPartNumberMarker(marker);
            var listing = execute("List multipart parts for " + upload.object(),
                    () -> client.listParts(request));
            listing.getParts().forEach(part -> parts.add(
                    new UploadedPart(part.getPartNumber(), part.getETag(), part.getSize())));
            truncated = listing.isTruncated();
            marker = listing.getNextPartNumberMarker();
        } while (truncated);
        return List.copyOf(parts);
    }

    @Override
    public void putLifecycleRule(String bucket, com.jdc.storeweave.core.capability.LifecycleRule rule) {
        String bucketName = requireText(bucket, "bucket");
        Objects.requireNonNull(rule, "rule");
        List<BucketLifecycleConfiguration.Rule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(existing -> rule.id().equals(existing.getId()));
        rules.add(new BucketLifecycleConfiguration.Rule()
                .withId(rule.id())
                .withStatus(rule.enabled()
                        ? BucketLifecycleConfiguration.ENABLED
                        : BucketLifecycleConfiguration.DISABLED)
                .withExpirationInDays(rule.expireAfterDays())
                .withFilter(new LifecycleFilter(new LifecyclePrefixPredicate(rule.prefix()))));
        setLifecycleRules(bucketName, rules);
    }

    @Override
    public List<com.jdc.storeweave.core.capability.LifecycleRule> listLifecycleRules(String bucket) {
        return sdkLifecycleRules(requireText(bucket, "bucket")).stream()
                .filter(rule -> rule.getExpirationInDays() > 0)
                .map(rule -> new com.jdc.storeweave.core.capability.LifecycleRule(
                        rule.getId(),
                        lifecyclePrefix(rule),
                        rule.getExpirationInDays(),
                        BucketLifecycleConfiguration.ENABLED.equals(rule.getStatus())))
                .toList();
    }

    @Override
    public void deleteLifecycleRule(String bucket, String ruleId) {
        String bucketName = requireText(bucket, "bucket");
        String id = requireText(ruleId, "ruleId");
        List<BucketLifecycleConfiguration.Rule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(rule -> id.equals(rule.getId()));
        if (rules.isEmpty()) {
            execute("Delete lifecycle configuration for " + bucketName,
                    () -> client.deleteBucketLifecycleConfiguration(bucketName));
        } else {
            setLifecycleRules(bucketName, rules);
        }
    }

    @Override
    public void close() {
        try {
            client.shutdown();
        } catch (RuntimeException exception) {
            throw TencentCosExceptionMapper.map("Close Tencent COS client", exception);
        }
    }

    private List<BucketLifecycleConfiguration.Rule> sdkLifecycleRules(String bucket) {
        try {
            BucketLifecycleConfiguration configuration = client.getBucketLifecycleConfiguration(bucket);
            return configuration == null || configuration.getRules() == null
                    ? List.of()
                    : List.copyOf(configuration.getRules());
        } catch (RuntimeException exception) {
            if (TencentCosExceptionMapper.isMissingLifecycle(exception)) {
                return List.of();
            }
            throw TencentCosExceptionMapper.map("Get lifecycle configuration for " + bucket, exception);
        }
    }

    private void setLifecycleRules(String bucket, List<BucketLifecycleConfiguration.Rule> rules) {
        execute("Set lifecycle configuration for " + bucket,
                () -> client.setBucketLifecycleConfiguration(
                        bucket, new BucketLifecycleConfiguration(rules)));
    }

    private static String lifecyclePrefix(BucketLifecycleConfiguration.Rule rule) {
        if (rule.getFilter() != null
                && rule.getFilter().getPredicate() instanceof LifecyclePrefixPredicate predicate) {
            return predicate.getPrefix();
        }
        return "";
    }

    private static com.jdc.storeweave.core.model.ObjectMetadata toCoreMetadata(
            StorageObject object,
            ObjectMetadata metadata) {
        return new com.jdc.storeweave.core.model.ObjectMetadata(
                object,
                metadata.getContentLength(),
                metadata.getETag(),
                metadata.getLastModified() == null ? Instant.EPOCH : metadata.getLastModified().toInstant(),
                metadata.getContentType(),
                metadata.getUserMetadata());
    }

    private <T> T execute(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            throw TencentCosExceptionMapper.map(operation, exception);
        }
    }

    private void execute(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            throw TencentCosExceptionMapper.map(operation, exception);
        }
    }

    private StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, providerType(), null, false);
    }

    private StorageException contentFailure(String message, IOException exception) {
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR, message, providerType(), exception, false);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
