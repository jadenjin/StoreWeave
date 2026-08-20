package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketInfo;
import com.jdc.storeweave.core.capability.BucketOperations;
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
import com.jdc.storeweave.core.model.ObjectMetadata;
import com.jdc.storeweave.core.model.PageResult;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.PutObjectResult;
import com.jdc.storeweave.core.model.StorageObject;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class S3StorageClient
        implements StorageClient, BucketOperations, PresignOperations, MultipartOperations {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final String name;
    private final Region region;
    private final boolean customEndpoint;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    S3StorageClient(
            String name,
            Region region,
            boolean customEndpoint,
            S3Client s3Client,
            S3Presigner presigner) {
        this.name = Objects.requireNonNull(name, "name");
        this.region = Objects.requireNonNull(region, "region");
        this.customEndpoint = customEndpoint;
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return S3StorageProvider.TYPE;
    }

    @Override
    public Set<StorageCapabilityType> capabilities() {
        return Set.of(
                StorageCapabilityType.BUCKETS,
                StorageCapabilityType.PRESIGNED_URLS,
                StorageCapabilityType.MULTIPART_UPLOADS);
    }

    @Override
    public PutObjectResult put(PutObjectRequest request) {
        Objects.requireNonNull(request, "request");
        software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder builder =
                software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(request.object().bucket())
                        .key(request.object().key())
                        .metadata(request.metadata());
        request.content().contentTypeValue().ifPresent(builder::contentType);

        PutObjectResponse response = execute(
                "Put object " + request.object(),
                () -> s3Client.putObject(builder.build(), requestBody(request.content())));
        return new PutObjectResult(request.object(), response.eTag(), response.versionId());
    }

    @Override
    public InputStream get(StorageObject object) {
        Objects.requireNonNull(object, "object");
        return execute(
                "Get object " + object,
                () -> s3Client.getObject(GetObjectRequest.builder()
                        .bucket(object.bucket())
                        .key(object.key())
                        .build()));
    }

    @Override
    public ObjectMetadata stat(StorageObject object) {
        Objects.requireNonNull(object, "object");
        var response = execute(
                "Stat object " + object,
                () -> s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(object.bucket())
                        .key(object.key())
                        .build()));
        return new ObjectMetadata(
                object,
                response.contentLength(),
                response.eTag(),
                response.lastModified() == null ? Instant.EPOCH : response.lastModified(),
                response.contentType(),
                response.metadata());
    }

    @Override
    public boolean exists(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(object.bucket())
                    .key(object.key())
                    .build());
            return true;
        } catch (RuntimeException exception) {
            if (S3ExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw S3ExceptionMapper.map("Check object " + object, exception);
        }
    }

    @Override
    public boolean delete(StorageObject object) {
        Objects.requireNonNull(object, "object");
        if (!exists(object)) {
            return false;
        }
        execute(
                "Delete object " + object,
                () -> s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(object.bucket())
                        .key(object.key())
                        .build()));
        return true;
    }

    @Override
    public PageResult<ObjectMetadata> list(ListObjectsRequest request) {
        Objects.requireNonNull(request, "request");
        var response = execute(
                "List objects in " + request.bucket(),
                () -> s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(request.bucket())
                        .prefix(request.prefix())
                        .continuationToken(request.continuationToken())
                        .maxKeys(request.pageSize())
                        .build()));

        List<ObjectMetadata> items = response.contents().stream()
                .map(object -> new ObjectMetadata(
                        new StorageObject(request.bucket(), object.key()),
                        object.size(),
                        object.eTag(),
                        object.lastModified() == null ? Instant.EPOCH : object.lastModified(),
                        null,
                        Map.of()))
                .toList();
        return new PageResult<>(items, response.nextContinuationToken());
    }

    @Override
    public boolean bucketExists(String bucket) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(requireText(bucket, "bucket")).build());
            return true;
        } catch (RuntimeException exception) {
            if (S3ExceptionMapper.isNotFound(exception)) {
                return false;
            }
            throw S3ExceptionMapper.map("Check bucket " + bucket, exception);
        }
    }

    @Override
    public void createBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        if (bucketExists(bucketName)) {
            return;
        }
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(bucketName);
        if (!customEndpoint && !Region.US_EAST_1.equals(region)) {
            request.createBucketConfiguration(CreateBucketConfiguration.builder()
                    .locationConstraint(BucketLocationConstraint.fromValue(region.id()))
                    .build());
        }
        execute("Create bucket " + bucketName, () -> s3Client.createBucket(request.build()));
    }

    @Override
    public void deleteBucket(String bucket) {
        String bucketName = requireText(bucket, "bucket");
        execute(
                "Delete bucket " + bucketName,
                () -> s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build()));
    }

    @Override
    public List<BucketInfo> listBuckets() {
        var response = execute(
                "List buckets",
                () -> s3Client.listBuckets(ListBucketsRequest.builder().build()));
        return response.buckets().stream()
                .map(bucket -> new BucketInfo(bucket.name(), bucket.creationDate()))
                .toList();
    }

    @Override
    public URI presignGet(PresignRequest request) {
        Objects.requireNonNull(request, "request");
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(request.object().bucket())
                .key(request.object().key())
                .overrideConfiguration(overrideConfiguration(request))
                .build();
        return execute(
                "Presign GET for " + request.object(),
                () -> URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                                .signatureDuration(request.expiresIn())
                                .getObjectRequest(getRequest)
                                .build())
                        .url()
                        .toString()));
    }

    @Override
    public URI presignPut(PresignRequest request) {
        Objects.requireNonNull(request, "request");
        software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder putRequest =
                software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(request.object().bucket())
                        .key(request.object().key())
                        .overrideConfiguration(overrideConfiguration(request));
        request.headers().entrySet().stream()
                .filter(entry -> "content-type".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .ifPresent(putRequest::contentType);

        return execute(
                "Presign PUT for " + request.object(),
                () -> URI.create(presigner.presignPutObject(PutObjectPresignRequest.builder()
                                .signatureDuration(request.expiresIn())
                                .putObjectRequest(putRequest.build())
                                .build())
                        .url()
                        .toString()));
    }

    @Override
    public MultipartUpload initiate(StorageObject object) {
        Objects.requireNonNull(object, "object");
        var response = execute(
                "Initiate multipart upload for " + object,
                () -> s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(object.bucket())
                        .key(object.key())
                        .build()));
        return new MultipartUpload(response.uploadId(), object);
    }

    @Override
    public UploadedPart uploadPart(MultipartUpload upload, int partNumber, ObjectContent content) {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(content, "content");
        if (content.length() < 0) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "Multipart uploads require a known content length",
                    providerType(),
                    null,
                    false);
        }
        var response = execute(
                "Upload multipart part " + partNumber + " for " + upload.object(),
                () -> s3Client.uploadPart(UploadPartRequest.builder()
                                .bucket(upload.object().bucket())
                                .key(upload.object().key())
                                .uploadId(upload.uploadId())
                                .partNumber(partNumber)
                                .contentLength(content.length())
                                .build(),
                        requestBody(content)));
        return new UploadedPart(partNumber, response.eTag(), content.length());
    }

    @Override
    public void complete(MultipartUpload upload, List<UploadedPart> parts) {
        Objects.requireNonNull(upload, "upload");
        if (parts == null || parts.isEmpty()) {
            throw new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    "At least one uploaded part is required",
                    providerType(),
                    null,
                    false);
        }
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();
        execute(
                "Complete multipart upload for " + upload.object(),
                () -> s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(upload.object().bucket())
                        .key(upload.object().key())
                        .uploadId(upload.uploadId())
                        .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                        .build()));
    }

    @Override
    public void abort(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        execute(
                "Abort multipart upload for " + upload.object(),
                () -> s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(upload.object().bucket())
                        .key(upload.object().key())
                        .uploadId(upload.uploadId())
                        .build()));
    }

    @Override
    public List<UploadedPart> listParts(MultipartUpload upload) {
        Objects.requireNonNull(upload, "upload");
        List<UploadedPart> result = new ArrayList<>();
        Integer marker = null;
        boolean truncated;
        do {
            Integer currentMarker = marker;
            var response = execute(
                    "List multipart parts for " + upload.object(),
                    () -> s3Client.listParts(ListPartsRequest.builder()
                            .bucket(upload.object().bucket())
                            .key(upload.object().key())
                            .uploadId(upload.uploadId())
                            .partNumberMarker(currentMarker)
                            .build()));
            response.parts().forEach(part ->
                    result.add(new UploadedPart(part.partNumber(), part.eTag(), part.size())));
            truncated = Boolean.TRUE.equals(response.isTruncated());
            marker = response.nextPartNumberMarker();
        } while (truncated);
        return List.copyOf(result);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            presigner.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            s3Client.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw S3ExceptionMapper.map("Close S3 client", failure);
        }
    }

    private AwsRequestOverrideConfiguration overrideConfiguration(PresignRequest request) {
        AwsRequestOverrideConfiguration.Builder override = AwsRequestOverrideConfiguration.builder();
        request.headers().forEach(override::putHeader);
        return override.build();
    }

    private RequestBody requestBody(ObjectContent content) {
        String contentType = content.contentTypeValue().orElse(DEFAULT_CONTENT_TYPE);
        ContentStreamProvider streamProvider = () -> {
            try {
                return content.openStream();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
        return content.length() >= 0
                ? RequestBody.fromContentProvider(streamProvider, content.length(), contentType)
                : RequestBody.fromContentProvider(streamProvider, contentType);
    }

    private <T> T execute(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            throw S3ExceptionMapper.map(operation, exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
