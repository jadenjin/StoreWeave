package com.jdc.storeweave.provider.local;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.capability.BucketInfo;
import com.jdc.storeweave.core.capability.BucketOperations;
import com.jdc.storeweave.core.capability.StorageCapabilityType;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.model.ListObjectsRequest;
import com.jdc.storeweave.core.model.ObjectMetadata;
import com.jdc.storeweave.core.model.PageResult;
import com.jdc.storeweave.core.model.PutObjectRequest;
import com.jdc.storeweave.core.model.PutObjectResult;
import com.jdc.storeweave.core.model.StorageObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class LocalStorageClient implements StorageClient, BucketOperations {

    private final String name;
    private final Path root;

    public LocalStorageClient(String name, Path root) {
        this.name = requireText(name, "name");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw providerError("Cannot create local storage root " + this.root, exception);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return LocalStorageProvider.TYPE;
    }

    @Override
    public Set<StorageCapabilityType> capabilities() {
        return Set.of(StorageCapabilityType.BUCKETS);
    }

    @Override
    public PutObjectResult put(PutObjectRequest request) {
        requireBucket(request.object().bucket());
        Path target = resolveObject(request.object());
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(root, ".storeweave-", ".upload");
            MessageDigest digest = sha256();
            try (InputStream source = request.content().openStream();
                 DigestInputStream digested = new DigestInputStream(source, digest)) {
                Files.copy(digested, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(temporary, target);
            String eTag = HexFormat.of().formatHex(digest.digest());
            return new PutObjectResult(request.object(), eTag, null);
        } catch (IOException exception) {
            throw providerError("Cannot write object " + request.object(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup after a failed write.
                }
            }
        }
    }

    @Override
    public InputStream get(StorageObject object) {
        Path path = requireObject(object);
        try {
            return Files.newInputStream(path);
        } catch (IOException exception) {
            throw providerError("Cannot read object " + object, exception);
        }
    }

    @Override
    public ObjectMetadata stat(StorageObject object) {
        Path path = requireObject(object);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new ObjectMetadata(
                    object,
                    attributes.size(),
                    digest(path),
                    attributes.lastModifiedTime().toInstant(),
                    Files.probeContentType(path),
                    Map.of());
        } catch (IOException exception) {
            throw providerError("Cannot inspect object " + object, exception);
        }
    }

    @Override
    public boolean exists(StorageObject object) {
        return Files.isRegularFile(resolveObject(object));
    }

    @Override
    public boolean delete(StorageObject object) {
        Path path = resolveObject(object);
        try {
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                removeEmptyParents(path.getParent(), resolveBucket(object.bucket()));
            }
            return deleted;
        } catch (IOException exception) {
            throw providerError("Cannot delete object " + object, exception);
        }
    }

    @Override
    public PageResult<ObjectMetadata> list(ListObjectsRequest request) {
        Path bucket = requireBucket(request.bucket());
        try (Stream<Path> paths = Files.walk(bucket)) {
            List<String> keys = paths
                    .filter(Files::isRegularFile)
                    .map(path -> bucket.relativize(path).toString().replace('\\', '/'))
                    .filter(key -> key.startsWith(request.prefix()))
                    .filter(key -> request.continuationToken() == null
                            || key.compareTo(request.continuationToken()) > 0)
                    .sorted()
                    .limit((long) request.pageSize() + 1)
                    .toList();
            boolean hasNext = keys.size() > request.pageSize();
            List<String> pageKeys = hasNext ? keys.subList(0, request.pageSize()) : keys;
            List<ObjectMetadata> items = pageKeys.stream()
                    .map(key -> stat(new StorageObject(request.bucket(), key)))
                    .toList();
            String nextToken = hasNext ? pageKeys.get(pageKeys.size() - 1) : null;
            return new PageResult<>(items, nextToken);
        } catch (IOException exception) {
            throw providerError("Cannot list bucket " + request.bucket(), exception);
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        return Files.isDirectory(resolveBucket(bucket));
    }

    @Override
    public void createBucket(String bucket) {
        try {
            Files.createDirectories(resolveBucket(bucket));
        } catch (IOException exception) {
            throw providerError("Cannot create bucket " + bucket, exception);
        }
    }

    @Override
    public void deleteBucket(String bucket) {
        Path path = requireBucket(bucket);
        try {
            Files.delete(path);
        } catch (java.nio.file.DirectoryNotEmptyException exception) {
            throw new StorageException(
                    StorageErrorCode.CONFLICT,
                    "Bucket is not empty: " + bucket,
                    providerType(),
                    exception,
                    false);
        } catch (IOException exception) {
            throw providerError("Cannot delete bucket " + bucket, exception);
        }
    }

    @Override
    public List<BucketInfo> listBuckets() {
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new BucketInfo(path.getFileName().toString(), creationTime(path)))
                    .toList();
        } catch (IOException exception) {
            throw providerError("Cannot list local buckets", exception);
        }
    }

    private Path requireBucket(String bucket) {
        Path path = resolveBucket(bucket);
        if (!Files.isDirectory(path)) {
            throw new StorageException(
                    StorageErrorCode.NOT_FOUND,
                    "Bucket does not exist: " + bucket,
                    providerType(),
                    null,
                    false);
        }
        return path;
    }

    private Path requireObject(StorageObject object) {
        Path path = resolveObject(object);
        if (!Files.isRegularFile(path)) {
            throw new StorageException(
                    StorageErrorCode.NOT_FOUND,
                    "Object does not exist: " + object,
                    providerType(),
                    null,
                    false);
        }
        return path;
    }

    private Path resolveBucket(String bucket) {
        String value = requireText(bucket, "bucket");
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST, "Invalid bucket name: " + value);
        }
        return ensureWithinRoot(root.resolve(value).normalize());
    }

    private Path resolveObject(StorageObject object) {
        Path bucket = resolveBucket(object.bucket());
        Path path = bucket.resolve(object.key()).normalize();
        if (!path.startsWith(bucket)) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST, "Invalid object key: " + object.key());
        }
        return ensureWithinRoot(path);
    }

    private Path ensureWithinRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new StorageException(StorageErrorCode.INVALID_REQUEST, "Path escapes local storage root");
        }
        return path;
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void removeEmptyParents(Path current, Path bucket) throws IOException {
        while (current != null && !current.equals(bucket)) {
            try {
                Files.delete(current);
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                return;
            }
            current = current.getParent();
        }
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static Instant creationTime(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).creationTime().toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    private StorageException providerError(String message, Exception cause) {
        return new StorageException(StorageErrorCode.PROVIDER_ERROR, message, providerType(), cause, false);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
