package com.jdc.storeweave.core.exception;

import java.util.Objects;
import java.util.Optional;

public class StorageException extends RuntimeException {

    private final StorageErrorCode code;
    private final String providerType;
    private final boolean retryable;

    public StorageException(StorageErrorCode code, String message) {
        this(code, message, null, null, false);
    }

    public StorageException(
            StorageErrorCode code,
            String message,
            String providerType,
            Throwable cause,
            boolean retryable) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.providerType = providerType;
        this.retryable = retryable;
    }

    public StorageErrorCode code() {
        return code;
    }

    public Optional<String> providerType() {
        return Optional.ofNullable(providerType);
    }

    public boolean retryable() {
        return retryable;
    }
}
