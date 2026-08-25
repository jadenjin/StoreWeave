package com.jdc.storeweave.provider.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.AbortMultipartUploadRequest;
import com.obs.services.model.CompleteMultipartUploadRequest;
import com.obs.services.model.InitiateMultipartUploadRequest;
import com.obs.services.model.InitiateMultipartUploadResult;
import com.obs.services.model.LifecycleConfiguration;
import com.obs.services.model.ListBucketsRequest;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ListPartsRequest;
import com.obs.services.model.ListPartsResult;
import com.obs.services.model.Multipart;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsBucket;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectResult;
import com.obs.services.model.TemporarySignatureRequest;
import com.obs.services.model.TemporarySignatureResponse;
import com.obs.services.model.UploadPartRequest;
import com.obs.services.model.UploadPartResult;

import java.io.IOException;
import java.io.InputStream;
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

final class InMemoryHuaweiObs extends ObsClient {

    private final Set<String> buckets = ConcurrentHashMap.newKeySet();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
    private final Map<String, List<LifecycleConfiguration.Rule>> lifecycle = new ConcurrentHashMap<>();

    InMemoryHuaweiObs() {
        super("fake-access-key", "fake-secret-key", "https://obs.example.com");
    }

    @Override
    public PutObjectResult putObject(
            String bucketName, String objectKey, InputStream input, ObjectMetadata metadata) {
        requireBucket(bucketName);
        try {
            byte[] bytes = input.readAllBytes();
            String eTag = eTag(bytes);
            objects.put(key(bucketName, objectKey), new StoredObject(
                    bytes, eTag, metadata.getContentType(),
                    stringMetadata(metadata), Date.from(Instant.now())));
            return new PutObjectResult(eTag, null, bucketName, objectKey, null, null);
        } catch (IOException exception) {
            throw new ObsException("Cannot read test object", exception);
        }
    }

    @Override
    public ObsObject getObject(String bucketName, String objectKey) {
        StoredObject stored = requireObject(bucketName, objectKey);
        ObsObject object = new ObsObject();
        object.setBucketName(bucketName);
        object.setObjectKey(objectKey);
        object.setMetadata(metadata(stored));
        object.setObjectContent(new java.io.ByteArrayInputStream(stored.bytes()));
        return object;
    }

    @Override
    public ObjectMetadata getObjectMetadata(String bucketName, String objectKey) {
        return metadata(requireObject(bucketName, objectKey));
    }

    @Override
    public boolean doesObjectExist(String bucketName, String objectKey) {
        requireBucket(bucketName);
        return objects.containsKey(key(bucketName, objectKey));
    }

    @Override
    public com.obs.services.model.DeleteObjectResult deleteObject(
            String bucketName, String objectKey) {
        requireBucket(bucketName);
        objects.remove(key(bucketName, objectKey));
        return null;
    }

    @Override
    public ObjectListing listObjects(ListObjectsRequest request) {
        requireBucket(request.getBucketName());
        String prefix = request.getPrefix() == null ? "" : request.getPrefix();
        String marker = request.getMarker();
        List<Map.Entry<String, StoredObject>> matches = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(request.getBucketName() + "/" + prefix))
                .filter(entry -> marker == null || objectName(entry.getKey()).compareTo(marker) > 0)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        List<Map.Entry<String, StoredObject>> page = matches.stream()
                .limit(request.getMaxKeys()).toList();
        List<ObsObject> summaries = page.stream().map(entry -> {
            ObsObject object = new ObsObject();
            object.setBucketName(request.getBucketName());
            object.setObjectKey(objectName(entry.getKey()));
            object.setMetadata(metadata(entry.getValue()));
            return object;
        }).toList();
        boolean truncated = matches.size() > page.size();
        String nextMarker = truncated ? objectName(page.get(page.size() - 1).getKey()) : null;
        return new ObjectListing(
                summaries, List.of(), request.getBucketName(), truncated, prefix, marker,
                request.getMaxKeys(), request.getDelimiter(), nextMarker, "test-region");
    }

    @Override
    public boolean headBucket(String bucketName) {
        if (!buckets.contains(bucketName)) {
            throw serviceException("NoSuchBucket", 404);
        }
        return true;
    }

    @Override
    public ObsBucket createBucket(String bucketName) {
        return create(bucketName, null);
    }

    @Override
    public ObsBucket createBucket(String bucketName, String location) {
        return create(bucketName, location);
    }

    private ObsBucket create(String bucketName, String location) {
        buckets.add(bucketName);
        ObsBucket bucket = new ObsBucket(bucketName, location);
        bucket.setCreationDate(Date.from(Instant.now()));
        return bucket;
    }

    @Override
    public com.obs.services.model.HeaderResponse deleteBucket(String bucketName) {
        if (objects.keySet().stream().anyMatch(value -> value.startsWith(bucketName + "/"))) {
            throw serviceException("BucketNotEmpty", 409);
        }
        buckets.remove(bucketName);
        return null;
    }

    @Override
    public List<ObsBucket> listBuckets(ListBucketsRequest request) {
        return buckets.stream().sorted().map(name -> {
            ObsBucket bucket = new ObsBucket(name, "test-region");
            bucket.setCreationDate(Date.from(Instant.EPOCH));
            return bucket;
        }).toList();
    }

    @Override
    public TemporarySignatureResponse createTemporarySignature(TemporarySignatureRequest request) {
        return new TemporarySignatureResponse(
                "https://bucket.obs.example.com/object?q-signature=fake");
    }

    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request) {
        requireBucket(request.getBucketName());
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadSession(
                request.getBucketName(), request.getObjectKey(), new ConcurrentHashMap<>()));
        return new InitiateMultipartUploadResult(
                request.getBucketName(), request.getObjectKey(), uploadId);
    }

    @Override
    public UploadPartResult uploadPart(UploadPartRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        try {
            byte[] bytes = request.getInput().readAllBytes();
            String eTag = eTag(bytes);
            session.parts().put(request.getPartNumber(), new UploadedBytes(bytes, eTag));
            UploadPartResult result = new UploadPartResult();
            result.setPartNumber(request.getPartNumber());
            result.setEtag(eTag);
            return result;
        } catch (IOException exception) {
            throw new ObsException("Cannot read test part", exception);
        }
    }

    @Override
    public ListPartsResult listParts(ListPartsRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        List<Multipart> parts = session.parts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Multipart(
                        entry.getKey(), Date.from(Instant.now()), entry.getValue().eTag(),
                        (long) entry.getValue().bytes().length))
                .toList();
        return new ListPartsResult(
                session.bucket(), session.objectKey(), request.getUploadId(), null, null, null,
                parts, parts.size(), false, null, null);
    }

    @Override
    public com.obs.services.model.HeaderResponse abortMultipartUpload(
            AbortMultipartUploadRequest request) {
        uploads.remove(request.getUploadId());
        return null;
    }

    @Override
    public com.obs.services.model.CompleteMultipartUploadResult completeMultipartUpload(
            CompleteMultipartUploadRequest request) {
        UploadSession session = requireUpload(request.getUploadId());
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        request.getPartEtag().stream()
                .sorted(Comparator.comparingInt(com.obs.services.model.PartEtag::getPartNumber))
                .forEach(part -> output.writeBytes(session.parts().get(part.getPartNumber()).bytes()));
        byte[] bytes = output.toByteArray();
        objects.put(key(session.bucket(), session.objectKey()), new StoredObject(
                bytes, eTag(bytes), "application/octet-stream", Map.of(), Date.from(Instant.now())));
        uploads.remove(request.getUploadId());
        return null;
    }

    @Override
    public LifecycleConfiguration getBucketLifecycle(String bucketName) {
        requireBucket(bucketName);
        List<LifecycleConfiguration.Rule> rules = lifecycle.get(bucketName);
        if (rules == null) {
            throw serviceException("NoSuchLifecycleConfiguration", 404);
        }
        return new LifecycleConfiguration(new ArrayList<>(rules));
    }

    @Override
    public com.obs.services.model.HeaderResponse setBucketLifecycle(
            String bucketName, LifecycleConfiguration configuration) {
        requireBucket(bucketName);
        lifecycle.put(bucketName, new ArrayList<>(configuration.getRules()));
        return null;
    }

    @Override
    public com.obs.services.model.HeaderResponse deleteBucketLifecycle(String bucketName) {
        requireBucket(bucketName);
        lifecycle.remove(bucketName);
        return null;
    }

    private void requireBucket(String bucket) {
        if (!buckets.contains(bucket)) {
            throw serviceException("NoSuchBucket", 404);
        }
    }

    private StoredObject requireObject(String bucket, String objectKey) {
        requireBucket(bucket);
        StoredObject stored = objects.get(key(bucket, objectKey));
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
        metadata.setContentLength((long) stored.bytes().length);
        metadata.setContentType(stored.contentType());
        metadata.setEtag(stored.eTag());
        metadata.setLastModified(stored.lastModified());
        stored.userMetadata().forEach(metadata::addUserMetadata);
        return metadata;
    }

    private static Map<String, String> stringMetadata(ObjectMetadata metadata) {
        Map<String, String> result = new LinkedHashMap<>();
        metadata.getAllMetadata().forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    private static ObsException serviceException(String code, int status) {
        ObsException exception = new ObsException(code);
        exception.setErrorCode(code);
        exception.setResponseCode(status);
        return exception;
    }

    private static String key(String bucket, String objectKey) {
        return bucket + "/" + objectKey;
    }

    private static String objectName(String storageKey) {
        return storageKey.substring(storageKey.indexOf('/') + 1);
    }

    private static String eTag(byte[] bytes) {
        return Integer.toHexString(java.util.Arrays.hashCode(bytes));
    }

    private record StoredObject(
            byte[] bytes, String eTag, String contentType,
            Map<String, String> userMetadata, Date lastModified) {
    }

    private record UploadSession(
            String bucket, String objectKey, Map<Integer, UploadedBytes> parts) {
    }

    private record UploadedBytes(byte[] bytes, String eTag) {
    }
}
