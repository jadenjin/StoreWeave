package com.jdc.storeweave.provider.huawei.obs;

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
import com.obs.services.ObsClient;
import com.obs.services.model.AbortMultipartUploadRequest;
import com.obs.services.model.CompleteMultipartUploadRequest;
import com.obs.services.model.HttpMethodEnum;
import com.obs.services.model.InitiateMultipartUploadRequest;
import com.obs.services.model.LifecycleConfiguration;
import com.obs.services.model.ListBucketsRequest;
import com.obs.services.model.ListPartsRequest;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.PartEtag;
import com.obs.services.model.TemporarySignatureRequest;
import com.obs.services.model.UploadPartRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class HuaweiObsStorageClient
        implements StorageClient, BucketOperations, PresignOperations, MultipartOperations, LifecycleOperations {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final String name;
    private final String region;
    private final ObsClient client;

    HuaweiObsStorageClient(String name, String region, ObsClient client) {
        this.name = Objects.requireNonNull(name, "name");
        this.region = region;
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return HuaweiObsStorageProvider.TYPE;
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
        request.metadata().forEach(metadata::addUserMetadata);
        try (InputStream stream = request.content().openStream()) {
            var result = client.putObject(
                    request.object().bucket(), request.object().key(), stream, metadata);
            return new PutObjectResult(request.object(), result.getEtag(), result.getVersionId());
        } catch (IOException exception) {
            throw HuaweiObsExceptionMapper.map("Read content for " + request.object(), exception);
        } catch (RuntimeException exception) {
            throw HuaweiObsExceptionMapper.map("Put object " + request.object(), exception);
        }
    }

    @Override
    public InputStream get(StorageObject object) {
        Objects.requireNonNull(object, "object");
        return execute("Get object " + object,
                () -> client.getObject(object.bucket(), object.key()).getObjectContent());
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
            if (HuaweiObsExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw HuaweiObsExceptionMapper.map("Check object " + object, exception);
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
        com.obs.services.model.ListObjectsRequest sdkRequest = new com.obs.services.model.ListObjectsRequest(
                request.bucket(), request.prefix(), request.continuationToken(), null, request.pageSize());
        var listing = execute("List objects in " + request.bucket(), () -> client.listObjects(sdkRequest));
        List<com.jdc.storeweave.core.model.ObjectMetadata> items = listing.getObjects().stream()
                .map(object -> toCoreMetadata(
                        new StorageObject(request.bucket(), object.getObjectKey()), object.getMetadata()))
                .toList();
        return new PageResult<>(items, listing.isTruncated() ? listing.getNextMarker() : null);
    }

    @Override
    public boolean bucketExists(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        try {
            return client.headBucket(bucketName);
        } catch (RuntimeException exception) {
            if (HuaweiObsExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw HuaweiObsExceptionMapper.map("Check bucket " + bucketName, exception);
        }
    }

    @Override
    public void createBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        if (!bucketExists(bucketName)) {
            if (region == null || region.isBlank()) {
                execute("Create bucket " + bucketName, () -> client.createBucket(bucketName));
            } else {
                execute("Create bucket " + bucketName, () -> client.createBucket(bucketName, region));
            }
        }
    }

    @Override
    public void deleteBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        execute("Delete bucket " + bucketName, () -> client.deleteBucket(bucketName));
    }

    @Override
    public List<BucketInfo> listBuckets() {
        return execute("List buckets", () -> client.listBuckets(new ListBucketsRequest()).stream()
                .map(bucket -> new BucketInfo(
                        bucket.getBucketName(),
                        bucket.getCreationDate() == null ? null : bucket.getCreationDate().toInstant()))
                .toList());
    }

    @Override
    public URI presignGet(PresignRequest request) {
        return presign(request, HttpMethodEnum.GET);
    }

    @Override
    public URI presignPut(PresignRequest request) {
        return presign(request, HttpMethodEnum.PUT);
    }

    private URI presign(PresignRequest request, HttpMethodEnum method) {
        Objects.requireNonNull(request, "request");
        long seconds = request.expiresIn().toSeconds();
        if (seconds < 1) {
            throw invalid("Huawei OBS presigned URL expiration must be at least one second");
        }
        TemporarySignatureRequest sdkRequest = new TemporarySignatureRequest(
                method, request.object().bucket(), request.object().key(), null, seconds);
        sdkRequest.getHeaders().putAll(request.headers());
        return execute("Presign " + method + " for " + request.object(),
                () -> URI.create(client.createTemporarySignature(sdkRequest).getSignedUrl()));
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
            throw invalid("Huawei OBS multipart part number must be between 1 and 10000");
        }
        if (content.length() < 0) {
            throw invalid("Multipart uploads require a known content length");
        }
        try (InputStream stream = content.openStream()) {
            UploadPartRequest request = new UploadPartRequest(
                    upload.object().bucket(), upload.object().key(), content.length(), stream);
            request.setUploadId(upload.uploadId());
            request.setPartNumber(partNumber);
            var result = client.uploadPart(request);
            return new UploadedPart(result.getPartNumber(), result.getEtag(), content.length());
        } catch (IOException exception) {
            throw HuaweiObsExceptionMapper.map(
                    "Read multipart content for " + upload.object(), exception);
        } catch (RuntimeException exception) {
            throw HuaweiObsExceptionMapper.map(
                    "Upload multipart part " + partNumber + " for " + upload.object(), exception);
        }
    }

    @Override
    public void complete(MultipartUpload upload, List<UploadedPart> parts) {
        Objects.requireNonNull(upload, "upload");
        if (parts == null || parts.isEmpty()) {
            throw invalid("At least one uploaded part is required");
        }
        List<PartEtag> partEtags = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> new PartEtag(part.eTag(), part.partNumber()))
                .toList();
        execute("Complete multipart upload for " + upload.object(),
                () -> client.completeMultipartUpload(new CompleteMultipartUploadRequest(
                        upload.object().bucket(), upload.object().key(), upload.uploadId(), partEtags)));
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
            listing.getMultipartList().forEach(part -> parts.add(new UploadedPart(
                    part.getPartNumber(), part.getEtag(), part.getSize())));
            truncated = listing.isTruncated();
            marker = truncated ? Integer.valueOf(listing.getNextPartNumberMarker()) : null;
        } while (truncated);
        return List.copyOf(parts);
    }

    @Override
    public void putLifecycleRule(String bucket, com.jdc.storeweave.core.capability.LifecycleRule rule) {
        String bucketName = requireText(bucket, "bucket");
        Objects.requireNonNull(rule, "rule");
        List<LifecycleConfiguration.Rule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(existing -> rule.id().equals(existing.getId()));
        LifecycleConfiguration configuration = new LifecycleConfiguration(rules);
        LifecycleConfiguration.Rule sdkRule = configuration.newRule(
                rule.id(), rule.prefix(), rule.enabled());
        LifecycleConfiguration.Expiration expiration = sdkRule.newExpiration();
        expiration.setDays(rule.expireAfterDays());
        sdkRule.setExpiration(expiration);
        setLifecycleRules(bucketName, configuration.getRules());
    }

    @Override
    public List<com.jdc.storeweave.core.capability.LifecycleRule> listLifecycleRules(String bucket) {
        return sdkLifecycleRules(requireText(bucket, "bucket")).stream()
                .filter(rule -> rule.getExpiration() != null && rule.getExpiration().getDays() != null)
                .map(rule -> new com.jdc.storeweave.core.capability.LifecycleRule(
                        rule.getId(),
                        rule.getPrefix(),
                        rule.getExpiration().getDays(),
                        Boolean.TRUE.equals(rule.getEnabled())))
                .toList();
    }

    @Override
    public void deleteLifecycleRule(String bucket, String ruleId) {
        String bucketName = requireText(bucket, "bucket");
        String id = requireText(ruleId, "ruleId");
        List<LifecycleConfiguration.Rule> rules = new ArrayList<>(sdkLifecycleRules(bucketName));
        rules.removeIf(rule -> id.equals(rule.getId()));
        if (rules.isEmpty()) {
            execute("Delete lifecycle configuration for " + bucketName,
                    () -> client.deleteBucketLifecycle(bucketName));
        } else {
            setLifecycleRules(bucketName, rules);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException exception) {
            throw HuaweiObsExceptionMapper.map("Close Huawei OBS client", exception);
        }
    }

    private List<LifecycleConfiguration.Rule> sdkLifecycleRules(String bucket) {
        try {
            LifecycleConfiguration configuration = client.getBucketLifecycle(bucket);
            return configuration == null || configuration.getRules() == null
                    ? List.of()
                    : List.copyOf(configuration.getRules());
        } catch (RuntimeException exception) {
            if (HuaweiObsExceptionMapper.isMissingLifecycle(exception)) {
                return List.of();
            }
            throw HuaweiObsExceptionMapper.map(
                    "Get lifecycle configuration for " + bucket, exception);
        }
    }

    private void setLifecycleRules(String bucket, List<LifecycleConfiguration.Rule> rules) {
        execute("Set lifecycle configuration for " + bucket,
                () -> client.setBucketLifecycle(bucket, new LifecycleConfiguration(rules)));
    }

    private static com.jdc.storeweave.core.model.ObjectMetadata toCoreMetadata(
            StorageObject object, ObjectMetadata metadata) {
        long size = metadata == null || metadata.getContentLength() == null
                ? 0L : metadata.getContentLength();
        Map<String, String> userMetadata = new LinkedHashMap<>();
        if (metadata != null && metadata.getAllMetadata() != null) {
            metadata.getAllMetadata().forEach((key, value) -> {
                if (value != null) {
                    userMetadata.put(key, String.valueOf(value));
                }
            });
        }
        return new com.jdc.storeweave.core.model.ObjectMetadata(
                object,
                size,
                metadata == null ? null : metadata.getEtag(),
                metadata == null || metadata.getLastModified() == null
                        ? Instant.EPOCH : metadata.getLastModified().toInstant(),
                metadata == null ? null : metadata.getContentType(),
                userMetadata);
    }

    private <T> T execute(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            throw HuaweiObsExceptionMapper.map(operation, exception);
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
}
