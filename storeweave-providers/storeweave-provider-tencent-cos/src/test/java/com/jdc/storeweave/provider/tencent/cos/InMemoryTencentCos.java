package com.jdc.storeweave.provider.tencent.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.AbortMultipartUploadRequest;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.BucketLifecycleConfiguration;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.InitiateMultipartUploadRequest;
import com.qcloud.cos.model.InitiateMultipartUploadResult;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ListPartsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PartListing;
import com.qcloud.cos.model.PartSummary;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.UploadPartRequest;
import com.qcloud.cos.model.UploadPartResult;
import com.qcloud.cos.region.Region;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryTencentCos extends COSClient {

    private final Set<String> buckets = ConcurrentHashMap.newKeySet();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
    private final Map<String, List<BucketLifecycleConfiguration.Rule>> lifecycle = new ConcurrentHashMap<>();

    InMemoryTencentCos() {
        super(new BasicCOSCredentials("fake-secret-id", "fake-secret-key"),
                new ClientConfig(new Region("ap-guangzhou")));
    }

    @Override
    public PutObjectResult putObject(
            String bucketName, String key, InputStream input, ObjectMetadata metadata) {
        requireBucket(bucketName);
        try {
            byte[] bytes = input.readAllBytes();
            String eTag = eTag(bytes);
            objects.put(objectKey(bucketName, key), new StoredObject(
                    bytes,
                    eTag,
                    metadata.getContentType(),
                    new LinkedHashMap<>(metadata.getUserMetadata()),
                    Date.from(Instant.now())));
            PutObjectResult result = new PutObjectResult();
            result.setETag(eTag);
            return result;
        } catch (IOException exception) {
            throw new CosClientException(exception);
        }
    }

    @Override
    public COSObject getObject(String bucketName, String key) {
        StoredObject stored = requireObject(bucketName, key);
        COSObject object = new COSObject() {
            @Override
            public void close() {
                // The SDK's test-only stream wrapper has no HTTP request to release.
            }
        };
        object.setBucketName(bucketName);
        object.setKey(key);
        object.setObjectMetadata(metadata(stored));
        object.setObjectContent(new java.io.ByteArrayInputStream(stored.bytes()));
        return object;
    }

    @Override
    public ObjectMetadata getObjectMetadata(String bucketName, String key) {
        return metadata(requireObject(bucketName, key));
    }

    @Override
    public boolean doesObjectExist(String bucketName, String key) {
        requireBucket(bucketName);
        return objects.containsKey(objectKey(bucketName, key));
    }

    @Override
    public void deleteObject(String bucketName, String key) {
        requireBucket(bucketName);
        objects.remove(objectKey(bucketName, key));
    }

    @Override
    public ObjectListing listObjects(ListObjectsRequest request) {
        requireBucket(request.getBucketName());
        String prefix = request.getPrefix() == null ? "" : request.getPrefix();
        String marker = request.getMarker();
        int maxKeys = request.getMaxKeys() == null ? 1000 : request.getMaxKeys();
        List<Map.Entry<String, StoredObject>> matches = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(request.getBucketName() + "/" + prefix))
                .filter(entry -> marker == null || objectName(entry.getKey()).compareTo(marker) > 0)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        List<Map.Entry<String, StoredObject>> page = matches.stream().limit(maxKeys).toList();
        ObjectListing listing = new ObjectListing();
        listing.setBucketName(request.getBucketName());
        listing.setPrefix(prefix);
        listing.setMarker(marker);
        listing.setMaxKeys(maxKeys);
        page.forEach(entry -> listing.getObjectSummaries().add(summary(
                request.getBucketName(), objectName(entry.getKey()), entry.getValue())));
        boolean truncated = matches.size() > page.size();
        listing.setTruncated(truncated);
        listing.setNextMarker(truncated ? objectName(page.get(page.size() - 1).getKey()) : null);
        return listing;
    }

    @Override
    public boolean doesBucketExist(String bucketName) {
        return buckets.contains(bucketName);
    }

    @Override
    public Bucket createBucket(String bucketName) {
        buckets.add(bucketName);
        Bucket bucket = new Bucket(bucketName);
        bucket.setCreationDate(Date.from(Instant.now()));
        return bucket;
    }

    @Override
    public void deleteBucket(String bucketName) {
        if (objects.keySet().stream().anyMatch(key -> key.startsWith(bucketName + "/"))) {
            throw serviceException("BucketNotEmpty", 409);
        }
        buckets.remove(bucketName);
    }

    @Override
    public List<Bucket> listBuckets() {
        return buckets.stream().sorted().map(name -> {
            Bucket bucket = new Bucket(name);
            bucket.setCreationDate(Date.from(Instant.EPOCH));
            return bucket;
        }).toList();
    }

    @Override
    public URL generatePresignedUrl(
            String bucketName,
            String key,
            Date expiration,
            HttpMethodName method,
            Map<String, String> headers,
            Map<String, String> parameters) {
        try {
            return new URL("https", bucketName + ".cos.ap-guangzhou.myqcloud.com",
                    "/" + key + "?q-sign-algorithm=sha1&q-signature=fake");
        } catch (MalformedURLException exception) {
            throw new CosClientException(exception);
        }
    }

    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request) {
        requireBucket(request.getBucketName());
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadSession(
                request.getBucketName(), request.getKey(), new ConcurrentHashMap<>()));
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setBucketName(request.getBucketName());
        result.setKey(request.getKey());
        result.setUploadId(uploadId);
        return result;
    }

    @Override
    public UploadPartResult uploadPart(UploadPartRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        try {
            byte[] bytes = request.getInputStream().readAllBytes();
            String eTag = eTag(bytes);
            session.parts().put(request.getPartNumber(), new UploadedBytes(bytes, eTag));
            UploadPartResult result = new UploadPartResult();
            result.setPartNumber(request.getPartNumber());
            result.setETag(eTag);
            return result;
        } catch (IOException exception) {
            throw new CosClientException(exception);
        }
    }

    @Override
    public PartListing listParts(ListPartsRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        List<PartSummary> summaries = session.parts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    PartSummary summary = new PartSummary();
                    summary.setPartNumber(entry.getKey());
                    summary.setETag(entry.getValue().eTag());
                    summary.setSize(entry.getValue().bytes().length);
                    summary.setLastModified(Date.from(Instant.now()));
                    return summary;
                }).toList();
        PartListing listing = new PartListing();
        listing.setBucketName(session.bucket());
        listing.setKey(session.key());
        listing.setUploadId(request.getUploadId());
        listing.setParts(summaries);
        listing.setTruncated(false);
        return listing;
    }

    @Override
    public void abortMultipartUpload(AbortMultipartUploadRequest request) {
        uploads.remove(request.getUploadId());
    }

    @Override
    public com.qcloud.cos.model.CompleteMultipartUploadResult completeMultipartUpload(
            CompleteMultipartUploadRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        request.getPartETags().stream()
                .sorted(Comparator.comparingInt(com.qcloud.cos.model.PartETag::getPartNumber))
                .forEach(part -> output.writeBytes(session.parts().get(part.getPartNumber()).bytes()));
        byte[] bytes = output.toByteArray();
        objects.put(objectKey(session.bucket(), session.key()), new StoredObject(
                bytes, eTag(bytes), "application/octet-stream", Map.of(), Date.from(Instant.now())));
        uploads.remove(request.getUploadId());
        return new com.qcloud.cos.model.CompleteMultipartUploadResult();
    }

    @Override
    public BucketLifecycleConfiguration getBucketLifecycleConfiguration(String bucketName) {
        requireBucket(bucketName);
        List<BucketLifecycleConfiguration.Rule> rules = lifecycle.get(bucketName);
        if (rules == null) {
            throw serviceException("NoSuchLifecycleConfiguration", 404);
        }
        return new BucketLifecycleConfiguration(new ArrayList<>(rules));
    }

    @Override
    public void setBucketLifecycleConfiguration(
            String bucketName, BucketLifecycleConfiguration configuration) {
        requireBucket(bucketName);
        lifecycle.put(bucketName, new ArrayList<>(configuration.getRules()));
    }

    @Override
    public void deleteBucketLifecycleConfiguration(String bucketName) {
        requireBucket(bucketName);
        lifecycle.remove(bucketName);
    }

    private void requireBucket(String bucket) {
        if (!buckets.contains(bucket)) {
            throw serviceException("NoSuchBucket", 404);
        }
    }

    private StoredObject requireObject(String bucket, String key) {
        requireBucket(bucket);
        StoredObject stored = objects.get(objectKey(bucket, key));
        if (stored == null) {
            throw serviceException("NoSuchKey", 404);
        }
        return stored;
    }

    private UploadSession requireUpload(String uploadId) {
        UploadSession upload = uploads.get(uploadId);
        if (upload == null) {
            throw serviceException("NoSuchUpload", 404);
        }
        return upload;
    }

    private static ObjectMetadata metadata(StoredObject stored) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(stored.bytes().length);
        metadata.setContentType(stored.contentType());
        metadata.setETag(stored.eTag());
        metadata.setLastModified(stored.lastModified());
        metadata.setUserMetadata(new LinkedHashMap<>(stored.userMetadata()));
        return metadata;
    }

    private static COSObjectSummary summary(String bucket, String key, StoredObject stored) {
        COSObjectSummary summary = new COSObjectSummary();
        summary.setBucketName(bucket);
        summary.setKey(key);
        summary.setSize(stored.bytes().length);
        summary.setETag(stored.eTag());
        summary.setLastModified(stored.lastModified());
        return summary;
    }

    private static CosServiceException serviceException(String code, int status) {
        CosServiceException exception = new CosServiceException(code);
        exception.setErrorCode(code);
        exception.setStatusCode(status);
        return exception;
    }

    private static String objectKey(String bucket, String key) {
        return bucket + "/" + key;
    }

    private static String objectName(String storageKey) {
        return storageKey.substring(storageKey.indexOf('/') + 1);
    }

    private static String eTag(byte[] bytes) {
        return Integer.toHexString(java.util.Arrays.hashCode(bytes));
    }

    private record StoredObject(
            byte[] bytes,
            String eTag,
            String contentType,
            Map<String, String> userMetadata,
            Date lastModified) {
    }

    private record UploadSession(
            String bucket,
            String key,
            Map<Integer, UploadedBytes> parts) {
    }

    private record UploadedBytes(byte[] bytes, String eTag) {
    }
}
