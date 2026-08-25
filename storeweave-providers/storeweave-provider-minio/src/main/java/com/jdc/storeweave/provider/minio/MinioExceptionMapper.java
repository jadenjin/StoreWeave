package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class MinioExceptionMapper {

    private static final Set<String> NOT_FOUND_CODES = Set.of(
            "NoSuchBucket", "NoSuchKey", "NoSuchObject", "NoSuchUpload",
            "NoSuchLifecycleConfiguration");
    private static final Set<String> UNAUTHORIZED_CODES = Set.of(
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch",
            "InvalidToken", "ExpiredToken");
    private static final Set<String> CONFLICT_CODES = Set.of(
            "BucketAlreadyExists", "BucketAlreadyOwnedByYou", "BucketNotEmpty");

    private MinioExceptionMapper() {
    }

    static StorageException map(String operation, Exception exception) {
        Throwable cause = unwrap(exception);
        if (cause instanceof StorageException storageException) {
            return storageException;
        }
        if (cause instanceof ErrorResponseException responseException) {
            String code = responseException.errorResponse() == null
                    ? null
                    : responseException.errorResponse().code();
            int status = responseException.response() == null
                    ? 0
                    : responseException.response().code();
            return new StorageException(
                    errorCode(status, code),
                    message(operation, status, code),
                    MinioStorageProvider.TYPE,
                    cause,
                    retryable(status));
        }
        if (cause instanceof ServerException serverException) {
            return new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    message(operation, serverException.statusCode(), null),
                    MinioStorageProvider.TYPE,
                    cause,
                    retryable(serverException.statusCode()));
        }
        if (cause instanceof InvalidResponseException || cause instanceof IOException) {
            return new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    operation + " failed because the MinIO service could not be reached",
                    MinioStorageProvider.TYPE,
                    cause,
                    true);
        }
        if (cause instanceof IllegalArgumentException) {
            return new StorageException(
                    StorageErrorCode.INVALID_REQUEST,
                    operation + " rejected an invalid request",
                    MinioStorageProvider.TYPE,
                    cause,
                    false);
        }
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR,
                operation + " failed",
                MinioStorageProvider.TYPE,
                cause,
                false);
    }

    static boolean isNotFound(Exception exception) {
        Throwable cause = unwrap(exception);
        if (!(cause instanceof ErrorResponseException responseException)) {
            return false;
        }
        String code = responseException.errorResponse() == null
                ? null
                : responseException.errorResponse().code();
        return code != null && NOT_FOUND_CODES.contains(code)
                || responseException.response() != null && responseException.response().code() == 404;
    }

    static boolean isMissingLifecycle(Exception exception) {
        Throwable cause = unwrap(exception);
        return cause instanceof ErrorResponseException responseException
                && responseException.errorResponse() != null
                && "NoSuchLifecycleConfiguration".equals(responseException.errorResponse().code());
    }

    private static StorageErrorCode errorCode(int status, String code) {
        if (code != null && NOT_FOUND_CODES.contains(code) || status == 404) {
            return StorageErrorCode.NOT_FOUND;
        }
        if (code != null && UNAUTHORIZED_CODES.contains(code) || status == 401 || status == 403) {
            return StorageErrorCode.UNAUTHORIZED;
        }
        if (code != null && CONFLICT_CODES.contains(code) || status == 409) {
            return StorageErrorCode.CONFLICT;
        }
        if (status == 400 || status == 405 || status == 411 || status == 412 || status == 416) {
            return StorageErrorCode.INVALID_REQUEST;
        }
        return StorageErrorCode.PROVIDER_ERROR;
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static String message(String operation, int status, String errorCode) {
        String statusText = status > 0 ? " status " + status : "";
        String codeText = errorCode == null || errorCode.isBlank() ? "" : " (" + errorCode + ")";
        return operation + " failed with MinIO" + statusText + codeText;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
