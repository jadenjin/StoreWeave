package com.jdc.storeweave.provider.ftp;

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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FtpStorageClient implements StorageClient, BucketOperations {

    private final String name;
    private final String root;
    private final FtpConnectionFactory connections;

    FtpStorageClient(String name, String root, FtpConnectionFactory connections) {
        this.name = Objects.requireNonNull(name, "name");
        this.root = Objects.requireNonNull(root, "root");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String providerType() {
        return FtpStorageProvider.TYPE;
    }

    @Override
    public Set<StorageCapabilityType> capabilities() {
        return Set.of(StorageCapabilityType.BUCKETS);
    }

    @Override
    public PutObjectResult put(PutObjectRequest request) {
        Objects.requireNonNull(request, "request");
        String target = objectPath(request.object());
        String operationId = UUID.randomUUID().toString();
        String temporary = target + ".uploading-" + operationId;
        String backup = target + ".backup-" + operationId;
        try (InputStream content = request.content().openStream()) {
            connections.execute(session -> {
                session.createDirectories(parent(target));
                boolean backupCreated = false;
                try {
                    session.store(temporary, content);
                    if (session.fileExists(target)) {
                        session.rename(target, backup);
                        backupCreated = true;
                    }
                    session.rename(temporary, target);
                } catch (IOException failure) {
                    safeDelete(session, temporary, failure);
                    if (backupCreated && isMissing(session, target, failure)) {
                        safeRename(session, backup, target, failure);
                    }
                    throw failure;
                }
                if (backupCreated) {
                    session.deleteFile(backup);
                }
                return null;
            });
            return new PutObjectResult(request.object(), null, null);
        } catch (IOException exception) {
            throw map("Put object " + request.object(), exception);
        }
    }

    @Override
    public InputStream get(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            return connections.openDownload(objectPath(object));
        } catch (IOException exception) {
            throw map("Get object " + object, exception);
        }
    }

    @Override
    public ObjectMetadata stat(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            FtpConnectionFactory.Entry entry = connections.execute(
                    session -> session.stat(objectPath(object)));
            if (entry == null || entry.directory()) {
                throw new FileNotFoundException(object.toString());
            }
            return metadata(object, entry);
        } catch (IOException exception) {
            throw map("Stat object " + object, exception);
        }
    }

    @Override
    public boolean exists(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            return connections.execute(session -> session.fileExists(objectPath(object)));
        } catch (IOException exception) {
            throw map("Check object " + object, exception);
        }
    }

    @Override
    public boolean delete(StorageObject object) {
        Objects.requireNonNull(object, "object");
        try {
            return connections.execute(session -> {
                String path = objectPath(object);
                if (!session.fileExists(path)) {
                    return false;
                }
                session.deleteFile(path);
                pruneEmptyParents(session, parent(path), bucketPath(object.bucket()));
                return true;
            });
        } catch (IOException exception) {
            throw map("Delete object " + object, exception);
        }
    }

    @Override
    public PageResult<ObjectMetadata> list(ListObjectsRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            List<ObjectMetadata> all = connections.execute(session -> {
                String bucketRoot = bucketPath(request.bucket());
                if (!session.directoryExists(bucketRoot)) {
                    throw new FileNotFoundException(request.bucket());
                }
                List<ObjectMetadata> result = new ArrayList<>();
                scan(session, request.bucket(), bucketRoot, result);
                return result;
            });
            List<ObjectMetadata> matches = all.stream()
                    .filter(item -> item.object().key().startsWith(request.prefix()))
                    .filter(item -> request.continuationToken() == null
                            || item.object().key().compareTo(request.continuationToken()) > 0)
                    .sorted(Comparator.comparing(item -> item.object().key()))
                    .toList();
            List<ObjectMetadata> page = matches.stream().limit(request.pageSize()).toList();
            String next = matches.size() > page.size()
                    ? page.get(page.size() - 1).object().key() : null;
            return new PageResult<>(page, next);
        } catch (IOException exception) {
            throw map("List objects in " + request.bucket(), exception);
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        try {
            return connections.execute(session -> session.directoryExists(bucketPath(bucket)));
        } catch (IOException exception) {
            throw map("Check bucket " + bucket, exception);
        }
    }

    @Override
    public void createBucket(String bucket) {
        try {
            connections.execute(session -> {
                session.createDirectories(bucketPath(bucket));
                return null;
            });
        } catch (IOException exception) {
            throw map("Create bucket " + bucket, exception);
        }
    }

    @Override
    public void deleteBucket(String bucket) {
        try {
            connections.execute(session -> {
                session.deleteDirectory(bucketPath(bucket));
                return null;
            });
        } catch (IOException exception) {
            throw map("Delete bucket " + bucket, exception);
        }
    }

    @Override
    public List<BucketInfo> listBuckets() {
        try {
            return connections.execute(session -> {
                if (!session.directoryExists(root)) {
                    return List.of();
                }
                return session.list(root).stream()
                        .filter(FtpConnectionFactory.Entry::directory)
                        .map(entry -> new BucketInfo(entry.name(), entry.modified()))
                        .sorted(Comparator.comparing(BucketInfo::name))
                        .toList();
            });
        } catch (IOException exception) {
            throw map("List buckets", exception);
        }
    }

    @Override
    public void close() {
        try {
            connections.close();
        } catch (Exception exception) {
            throw map("Close FTP client", new IOException(exception));
        }
    }

    private void scan(
            FtpConnectionFactory.Session session,
            String bucket,
            String directory,
            List<ObjectMetadata> result) throws IOException {
        for (FtpConnectionFactory.Entry entry : session.list(directory)) {
            if (entry.directory()) {
                scan(session, bucket, entry.path(), result);
            } else {
                String key = entry.path().substring(bucketPath(bucket).length() + 1);
                result.add(metadata(new StorageObject(bucket, key), entry));
            }
        }
    }

    private static ObjectMetadata metadata(StorageObject object, FtpConnectionFactory.Entry entry) {
        return new ObjectMetadata(
                object, entry.size(), null,
                entry.modified() == null ? Instant.EPOCH : entry.modified(),
                null, Map.of());
    }

    private String bucketPath(String bucket) {
        return child(root, validateBucket(bucket));
    }

    private String objectPath(StorageObject object) {
        return child(bucketPath(object.bucket()), validateObjectKey(object.key()));
    }

    private static String validateBucket(String bucket) {
        if (bucket == null || bucket.isBlank() || bucket.contains("/") || bucket.contains("\\")
                || ".".equals(bucket) || "..".equals(bucket) || hasControlCharacter(bucket)) {
            throw invalid("FTP bucket must be a single safe directory name");
        }
        return bucket;
    }

    private static String validateObjectKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.endsWith("/")
                || key.contains("\\") || hasControlCharacter(key)) {
            throw invalid("FTP object key must be a safe relative file path");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("FTP object key contains an invalid path segment");
            }
        }
        return key;
    }

    private StorageException map(String operation, IOException exception) {
        if (exception instanceof FileNotFoundException) {
            return new StorageException(
                    StorageErrorCode.NOT_FOUND, operation + " did not find the remote path",
                    providerType(), exception, false);
        }
        if (exception instanceof CommonsNetFtpConnectionFactory.FtpAuthenticationException) {
            return new StorageException(
                    StorageErrorCode.UNAUTHORIZED, operation + " was rejected by FTP authentication",
                    providerType(), exception, false);
        }
        if (exception instanceof CommonsNetFtpConnectionFactory.FtpConflictException) {
            return new StorageException(
                    StorageErrorCode.CONFLICT, operation + " conflicts with remote FTP state",
                    providerType(), exception, false);
        }
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR, operation + " failed",
                providerType(), exception, true);
    }

    private static void safeDelete(
            FtpConnectionFactory.Session session, String path, IOException original) {
        try {
            if (session.fileExists(path)) {
                session.deleteFile(path);
            }
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static void safeRename(
            FtpConnectionFactory.Session session,
            String source,
            String target,
            IOException original) {
        try {
            session.rename(source, target);
        } catch (IOException restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    private static boolean isMissing(
            FtpConnectionFactory.Session session, String path, IOException original) {
        try {
            return !session.fileExists(path);
        } catch (IOException checkFailure) {
            original.addSuppressed(checkFailure);
            return false;
        }
    }

    private static void pruneEmptyParents(
            FtpConnectionFactory.Session session,
            String directory,
            String bucketRoot) throws IOException {
        String current = directory;
        while (!current.equals(bucketRoot) && session.list(current).isEmpty()) {
            session.deleteDirectory(current);
            current = parent(current);
        }
    }

    private static String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    private static String child(String parent, String child) {
        return "/".equals(parent) ? "/" + child : parent + "/" + child;
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static StorageException invalid(String message) {
        return new StorageException(
                StorageErrorCode.INVALID_REQUEST, message, FtpStorageProvider.TYPE, null, false);
    }
}
