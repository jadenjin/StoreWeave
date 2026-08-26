package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;

final class S3ExceptionMapper {

    private S3ExceptionMapper() {
    }

    static StorageException map(
            String providerType, String operation, RuntimeException exception) {
        if (exception instanceof StorageException storageException) {
            return storageException;
        }
        if (exception instanceof AwsServiceException serviceException) {
            int status = serviceException.statusCode();
            String errorCode = serviceException.awsErrorDetails() == null
                    ? null
                    : serviceException.awsErrorDetails().errorCode();
            return new StorageException(
                    errorCode(status),
                    message(operation, status, errorCode),
                    providerType,
                    exception,
                    retryable(status));
        }
        if (exception instanceof SdkClientException || exception instanceof SdkException) {
            return new StorageException(
                    StorageErrorCode.PROVIDER_ERROR,
                    operation + " failed because the S3 service could not be reached",
                    providerType,
                    exception,
                    true);
        }
        return new StorageException(
                StorageErrorCode.PROVIDER_ERROR,
                operation + " failed",
                providerType,
                exception,
                false);
    }

    static boolean isNotFound(RuntimeException exception) {
        return exception instanceof AwsServiceException serviceException
                && serviceException.statusCode() == 404;
    }

    private static StorageErrorCode errorCode(int status) {
        return switch (status) {
            case 400, 405, 411, 412, 416 -> StorageErrorCode.INVALID_REQUEST;
            case 401, 403 -> StorageErrorCode.UNAUTHORIZED;
            case 404 -> StorageErrorCode.NOT_FOUND;
            case 409 -> StorageErrorCode.CONFLICT;
            default -> StorageErrorCode.PROVIDER_ERROR;
        };
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static String message(String operation, int status, String errorCode) {
        String suffix = errorCode == null || errorCode.isBlank() ? "" : " (" + errorCode + ")";
        return operation + " failed with S3 status " + status + suffix;
    }
}
