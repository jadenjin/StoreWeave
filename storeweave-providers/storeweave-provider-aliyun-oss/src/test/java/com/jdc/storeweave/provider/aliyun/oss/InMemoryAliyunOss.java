package com.jdc.storeweave.provider.aliyun.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.Bucket;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadResult;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.LifecycleRule;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PartSummary;
import com.aliyun.oss.model.PutObjectResult;
import com.aliyun.oss.model.SetBucketLifecycleRequest;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryAliyunOss implements InvocationHandler {

    private final Map<String, Map<String, StoredObject>> buckets = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
    private final Map<String, List<LifecycleRule>> lifecycleRules = new ConcurrentHashMap<>();

    OSS client() {
        return (OSS) Proxy.newProxyInstance(
                OSS.class.getClassLoader(),
                new Class<?>[]{OSS.class},
                this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        Object[] args = arguments == null ? new Object[0] : arguments;
        return switch (method.getName()) {
            case "toString" -> "InMemoryAliyunOss";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "doesBucketExist" -> buckets.containsKey(bucketName(args[0]));
            case "createBucket" -> createBucket((String) args[0]);
            case "deleteBucket" -> deleteBucket((String) args[0]);
            case "listBuckets" -> listBuckets();
            case "putObject" -> putObject(args);
            case "getObject" -> getObject((String) args[0], (String) args[1]);
            case "getObjectMetadata", "headObject" -> metadata((String) args[0], (String) args[1]);
            case "doesObjectExist" -> objectExists((String) args[0], (String) args[1]);
            case "deleteObject" -> deleteObject((String) args[0], (String) args[1]);
            case "listObjects" -> listObjects((ListObjectsRequest) args[0]);
            case "generatePresignedUrl" -> presign((GeneratePresignedUrlRequest) args[0]);
            case "initiateMultipartUpload" -> initiate((InitiateMultipartUploadRequest) args[0]);
            case "uploadPart" -> uploadPart((UploadPartRequest) args[0]);
            case "listParts" -> listParts((ListPartsRequest) args[0]);
            case "completeMultipartUpload" -> complete((CompleteMultipartUploadRequest) args[0]);
            case "abortMultipartUpload" -> abort(args[0]);
            case "getBucketLifecycle" -> getLifecycle((String) args[0]);
            case "setBucketLifecycle" -> setLifecycle((SetBucketLifecycleRequest) args[0]);
            case "deleteBucketLifecycle" -> deleteLifecycle((String) args[0]);
            case "shutdown" -> null;
            default -> throw new UnsupportedOperationException("Unsupported OSS test operation: " + method);
        };
    }

    private Bucket createBucket(String bucket) {
        buckets.computeIfAbsent(bucket, ignored -> new ConcurrentHashMap<>());
        Bucket result = new Bucket(bucket);
        result.setCreationDate(new Date());
        return result;
    }

    private Object deleteBucket(String bucket) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects != null && !objects.isEmpty()) {
            throw ossError("BucketNotEmpty");
        }
        buckets.remove(bucket);
        lifecycleRules.remove(bucket);
        return null;
    }

    private List<Bucket> listBuckets() {
        return buckets.keySet().stream().sorted().map(name -> {
            Bucket bucket = new Bucket(name);
            bucket.setCreationDate(new Date(0));
            return bucket;
        }).toList();
    }

    private PutObjectResult putObject(Object[] args) throws IOException {
        String bucket = (String) args[0];
        String key = (String) args[1];
        InputStream stream = (InputStream) args[2];
        ObjectMetadata metadata = (ObjectMetadata) args[3];
        Map<String, StoredObject> objects = requiredBucket(bucket);
        byte[] bytes = stream.readAllBytes();
        String eTag = digest(bytes);
        objects.put(key, new StoredObject(
                bytes,
                metadata.getContentType(),
                Map.copyOf(metadata.getUserMetadata()),
                Instant.now(),
                eTag));
        PutObjectResult result = new PutObjectResult();
        result.setETag(eTag);
        return result;
    }

    private OSSObject getObject(String bucket, String key) {
        StoredObject stored = requiredObject(bucket, key);
        OSSObject result = new OSSObject();
        result.setBucketName(bucket);
        result.setKey(key);
        result.setObjectMetadata(toMetadata(stored));
        result.setObjectContent(new ByteArrayInputStream(stored.content()));
        return result;
    }

    private ObjectMetadata metadata(String bucket, String key) {
        return toMetadata(requiredObject(bucket, key));
    }

    private boolean objectExists(String bucket, String key) {
        return buckets.containsKey(bucket) && buckets.get(bucket).containsKey(key);
    }

    private Object deleteObject(String bucket, String key) {
        requiredBucket(bucket).remove(key);
        return null;
    }

    private ObjectListing listObjects(ListObjectsRequest request) {
        Map<String, StoredObject> objects = requiredBucket(request.getBucketName());
        String prefix = request.getPrefix() == null ? "" : request.getPrefix();
        int maxKeys = request.getMaxKeys() == null ? 1000 : request.getMaxKeys();
        List<Map.Entry<String, StoredObject>> matches = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .filter(entry -> request.getMarker() == null || entry.getKey().compareTo(request.getMarker()) > 0)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        boolean truncated = matches.size() > maxKeys;
        List<Map.Entry<String, StoredObject>> page = matches.subList(0, Math.min(maxKeys, matches.size()));
        ObjectListing result = new ObjectListing();
        result.setBucketName(request.getBucketName());
        result.setPrefix(prefix);
        result.setMaxKeys(maxKeys);
        result.setTruncated(truncated);
        if (truncated && !page.isEmpty()) {
            result.setNextMarker(page.get(page.size() - 1).getKey());
        }
        page.forEach(entry -> {
            OSSObjectSummary summary = new OSSObjectSummary();
            summary.setBucketName(request.getBucketName());
            summary.setKey(entry.getKey());
            summary.setSize(entry.getValue().content().length);
            summary.setETag(entry.getValue().eTag());
            summary.setLastModified(Date.from(entry.getValue().lastModified()));
            result.addObjectSummary(summary);
        });
        return result;
    }

    private URL presign(GeneratePresignedUrlRequest request) throws MalformedURLException {
        return new URL("http://127.0.0.1/" + request.getBucketName() + "/" + request.getKey()
                + "?OSSAccessKeyId=test&Signature=test");
    }

    private InitiateMultipartUploadResult initiate(InitiateMultipartUploadRequest request) {
        requiredBucket(request.getBucketName());
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadSession(request.getBucketName(), request.getKey()));
        InitiateMultipartUploadResult result = new InitiateMultipartUploadResult();
        result.setBucketName(request.getBucketName());
        result.setKey(request.getKey());
        result.setUploadId(uploadId);
        return result;
    }

    private UploadPartResult uploadPart(UploadPartRequest request) throws IOException {
        UploadSession upload = requiredUpload(request.getUploadId());
        byte[] bytes = request.getInputStream().readAllBytes();
        String eTag = digest(bytes);
        upload.parts().put(request.getPartNumber(), bytes);
        upload.eTags().put(request.getPartNumber(), eTag);
        UploadPartResult result = new UploadPartResult();
        result.setPartNumber(request.getPartNumber());
        result.setPartSize(bytes.length);
        result.setETag(eTag);
        return result;
    }

    private PartListing listParts(ListPartsRequest request) {
        UploadSession upload = requiredUpload(request.getUploadId());
        PartListing result = new PartListing();
        result.setBucketName(upload.bucket());
        result.setKey(upload.key());
        result.setUploadId(request.getUploadId());
        result.setTruncated(false);
        result.setMaxParts(1000);
        result.setPartNumberMarker(0);
        result.setNextPartNumberMarker(0);
        upload.parts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            PartSummary part = new PartSummary();
            part.setPartNumber(entry.getKey());
            part.setSize(entry.getValue().length);
            part.setETag(upload.eTags().get(entry.getKey()));
            part.setLastModified(new Date());
            result.addPart(part);
        });
        return result;
    }

    private CompleteMultipartUploadResult complete(CompleteMultipartUploadRequest request) {
        UploadSession upload = requiredUpload(request.getUploadId());
        List<byte[]> ordered = request.getPartETags().stream()
                .sorted(Comparator.comparingInt(com.aliyun.oss.model.PartETag::getPartNumber))
                .map(part -> upload.parts().get(part.getPartNumber()))
                .toList();
        int size = ordered.stream().mapToInt(bytes -> bytes.length).sum();
        byte[] content = new byte[size];
        int offset = 0;
        for (byte[] bytes : ordered) {
            System.arraycopy(bytes, 0, content, offset, bytes.length);
            offset += bytes.length;
        }
        String eTag = digest(content);
        requiredBucket(upload.bucket()).put(upload.key(), new StoredObject(
                content, "application/octet-stream", Map.of(), Instant.now(), eTag));
        uploads.remove(request.getUploadId());
        CompleteMultipartUploadResult result = new CompleteMultipartUploadResult();
        result.setBucketName(upload.bucket());
        result.setKey(upload.key());
        result.setETag(eTag);
        return result;
    }

    private Object abort(Object requestValue) {
        com.aliyun.oss.model.AbortMultipartUploadRequest request =
                (com.aliyun.oss.model.AbortMultipartUploadRequest) requestValue;
        uploads.remove(request.getUploadId());
        return null;
    }

    private List<LifecycleRule> getLifecycle(String bucket) {
        requiredBucket(bucket);
        return new ArrayList<>(lifecycleRules.getOrDefault(bucket, List.of()));
    }

    private Object setLifecycle(SetBucketLifecycleRequest request) {
        lifecycleRules.put(request.getBucketName(), new ArrayList<>(request.getLifecycleRules()));
        return null;
    }

    private Object deleteLifecycle(String bucket) {
        lifecycleRules.remove(bucket);
        return null;
    }

    private Map<String, StoredObject> requiredBucket(String bucket) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            throw ossError("NoSuchBucket");
        }
        return objects;
    }

    private StoredObject requiredObject(String bucket, String key) {
        StoredObject object = requiredBucket(bucket).get(key);
        if (object == null) {
            throw ossError("NoSuchKey");
        }
        return object;
    }

    private UploadSession requiredUpload(String uploadId) {
        UploadSession upload = uploads.get(uploadId);
        if (upload == null) {
            throw ossError("NoSuchUpload");
        }
        return upload;
    }

    private static String bucketName(Object value) {
        if (value instanceof String string) {
            return string;
        }
        return ((com.aliyun.oss.model.GenericRequest) value).getBucketName();
    }

    private static ObjectMetadata toMetadata(StoredObject stored) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(stored.content().length);
        metadata.setContentType(stored.contentType());
        metadata.setUserMetadata(stored.metadata());
        metadata.setLastModified(Date.from(stored.lastModified()));
        metadata.setHeader("ETag", stored.eTag());
        return metadata;
    }

    private static OSSException ossError(String code) {
        return new OSSException(code, code, "request-id", "host", "resource", "", "TEST");
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record StoredObject(
            byte[] content,
            String contentType,
            Map<String, String> metadata,
            Instant lastModified,
            String eTag) {
    }

    private record UploadSession(
            String bucket,
            String key,
            Map<Integer, byte[]> parts,
            Map<Integer, String> eTags) {

        private UploadSession(String bucket, String key) {
            this(bucket, key, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }
}
