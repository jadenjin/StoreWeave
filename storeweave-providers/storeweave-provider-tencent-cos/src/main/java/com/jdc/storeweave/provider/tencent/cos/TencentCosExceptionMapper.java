package com.jdc.storeweave.provider.tencent.cos;

import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;

import java.io.IOException;
import java.util.Set;

final class TencentCosExceptionMapper {

    private static final Set<String> NOT_FOUND = Set.of(
            "NoSuchBucket", "NoSuchKey", "NoSuchObject", "NoSuchUpload",
            "NoSuchLifecycleConfiguration");
    private static final Set<String> UNAUTHORIZED = Set.of(
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch",
            "ExpiredToken", "InvalidToken");
    private static final Set<String> CONFLICT = Set.of(
            "BucketAlreadyExists", "BucketAlreadyOwnedByYou", "BucketNotEmpty");
    private static final Set<String> INVALID = Set.of(
            "InvalidArgument", "InvalidBucketName", "InvalidObjectName", "InvalidPart",
            "InvalidPartOrder", "EntityTooLarge", "EntityTooSmall", "MalformedXML");
    private static final Set<String> RETRYABLE = Set.of(
            "RequestTimeout", "SlowDown", "ServiceUnavailable", "InternalError");

    private TencentCosExceptionMapper() {
    }

    static StorageException map(String operation, RuntimeException exception) {
        if (exception instanceof StorageException storageException) {
            return storageException;
        }
        if (exception instanceof CosServiceException serviceException) {
            String code = serviceException.getErrorCode();
            boolean retryable = serviceException.getStatusCode() >= 500
                    || (code != null && RETRYABLE.contains(code));
            return new StorageException(
                    errorCode(code, serviceException.getStatusCode()),
                    operation + " failed with Tencent COS" + codeSuffix(code),
                    TencentCosStorageProvider.TYPE,
                    exception,
                    retryable);
        }
        if (exception instanceof CosClientException clientException) {
            Throwable cause = clientException.getCause();
            boolean retryable = clientException.isRetryable()
                    || clientException.isRequestTimeout()
                    || cause instanceof IOException;
            return new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    operation + " failed because Tencent COS could not be reached",
                    TencentCosStorageProvider.TYPE,
                    exception,
                    retryable);
        }
        if (exception instanceof IllegalArgumentException) {
            return new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    operation + " rejected an invalid request",
                    TencentCosStorageProvider.TYPE,
                    exception,
                    false);
        }
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR,
                operation + " failed",
                TencentCosStorageProvider.TYPE,
                exception,
                false);
    }

    static boolean isNotFound(RuntimeException exception) {
        return exception instanceof CosServiceException serviceException
                && (serviceException.getStatusCode() == 404
                || (serviceException.getErrorCode() != null
                && NOT_FOUND.contains(serviceException.getErrorCode())));
    }

    static boolean isMissingLifecycle(RuntimeException exception) {
        return exception instanceof CosServiceException serviceException
                && (serviceException.getStatusCode() == 404
                || "NoSuchLifecycleConfiguration".equals(serviceException.getErrorCode()));
    }

    private static StorageErrorCode errorCode(String code, int status) {
        if (status == 404 || (code != null && NOT_FOUND.contains(code))) {
            return StorageErrorCode.NOT_FOUND;
        }
        if (status == 401 || status == 403 || (code != null && UNAUTHORIZED.contains(code))) {
            return StorageErrorCode.UNAUTHORIZED;
        }
        if (status == 409 || (code != null && CONFLICT.contains(code))) {
            return StorageErrorCode.CONFLICT;
        }
        if (status == 400 || (code != null && INVALID.contains(code))) {
            return StorageErrorCode.INVALID_REQUEST;
        }
        return StorageErrorCode.PROVIDER_ERROR;
    }

    private static String codeSuffix(String code) {
        return code == null || code.isBlank() ? "" : " (" + code + ")";
    }
}
