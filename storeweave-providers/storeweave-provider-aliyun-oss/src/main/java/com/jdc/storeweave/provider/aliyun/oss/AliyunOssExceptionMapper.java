package com.jdc.storeweave.provider.aliyun.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;

import java.io.IOException;
import java.util.Set;

final class AliyunOssExceptionMapper {

    private static final Set<String> NOT_FOUND = Set.of(
            "NoSuchBucket", "NoSuchKey", "NoSuchObject", "NoSuchUpload",
            "NoSuchLifecycle", "NoSuchLifecycleConfiguration");
    private static final Set<String> UNAUTHORIZED = Set.of(
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch",
            "SecurityTokenExpired", "InvalidSecurityToken");
    private static final Set<String> CONFLICT = Set.of(
            "BucketAlreadyExists", "BucketAlreadyOwnedByYou", "BucketNotEmpty");
    private static final Set<String> INVALID = Set.of(
            "InvalidArgument", "InvalidBucketName", "InvalidObjectName", "InvalidPart",
            "InvalidPartOrder", "EntityTooLarge", "EntityTooSmall", "MalformedXML");
    private static final Set<String> RETRYABLE = Set.of(
            "RequestTimeout", "RequestTimeTooSkewed", "SlowDown", "ServiceUnavailable", "InternalError");

    private AliyunOssExceptionMapper() {
    }

    static StorageException map(String operation, RuntimeException exception) {
        if (exception instanceof StorageException storageException) {
            return storageException;
        }
        if (exception instanceof OSSException ossException) {
            String code = ossException.getErrorCode();
            return new StorageException(
                    errorCode(code),
                    operation + " failed with Aliyun OSS" + codeSuffix(code),
                    AliyunOssStorageProvider.TYPE,
                    exception,
                    code != null && RETRYABLE.contains(code));
        }
        if (exception instanceof ClientException clientException) {
            Throwable cause = clientException.getCause();
            boolean retryable = cause instanceof IOException
                    || clientException.getErrorCode() == null
                    || clientException.getErrorCode().isBlank();
            return new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    operation + " failed because Aliyun OSS could not be reached",
                    AliyunOssStorageProvider.TYPE,
                    exception,
                    retryable);
        }
        if (exception instanceof IllegalArgumentException) {
            return new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    operation + " rejected an invalid request",
                    AliyunOssStorageProvider.TYPE,
                    exception,
                    false);
        }
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR,
                operation + " failed",
                AliyunOssStorageProvider.TYPE,
                exception,
                false);
    }

    static boolean isNotFound(RuntimeException exception) {
        return exception instanceof OSSException ossException
                && ossException.getErrorCode() != null
                && NOT_FOUND.contains(ossException.getErrorCode());
    }

    static boolean isMissingLifecycle(RuntimeException exception) {
        if (!(exception instanceof OSSException ossException)) {
            return false;
        }
        return "NoSuchLifecycle".equals(ossException.getErrorCode())
                || "NoSuchLifecycleConfiguration".equals(ossException.getErrorCode());
    }

    private static StorageErrorCode errorCode(String code) {
        if (code != null && NOT_FOUND.contains(code)) {
            return StorageErrorCode.NOT_FOUND;
        }
        if (code != null && UNAUTHORIZED.contains(code)) {
            return StorageErrorCode.UNAUTHORIZED;
        }
        if (code != null && CONFLICT.contains(code)) {
            return StorageErrorCode.CONFLICT;
        }
        if (code != null && INVALID.contains(code)) {
            return StorageErrorCode.INVALID_REQUEST;
        }
        return StorageErrorCode.PROVIDER_ERROR;
    }

    private static String codeSuffix(String code) {
        return code == null || code.isBlank() ? "" : " (" + code + ")";
    }
}
