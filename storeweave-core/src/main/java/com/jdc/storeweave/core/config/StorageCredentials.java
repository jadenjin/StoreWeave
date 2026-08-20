package com.jdc.storeweave.core.config;

import java.util.Objects;
import java.util.Optional;

/** Credentials with a redacted string representation. */
public final class StorageCredentials {

    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;

    public StorageCredentials(String accessKey, String secretKey, String sessionToken) {
        this.accessKey = requireText(accessKey, "accessKey");
        this.secretKey = requireText(secretKey, "secretKey");
        this.sessionToken = sessionToken;
    }

    public static StorageCredentials of(String accessKey, String secretKey) {
        return new StorageCredentials(accessKey, secretKey, null);
    }

    public String accessKey() {
        return accessKey;
    }

    public String secretKey() {
        return secretKey;
    }

    public Optional<String> sessionToken() {
        return Optional.ofNullable(sessionToken).filter(value -> !value.isBlank());
    }

    @Override
    public String toString() {
        return "StorageCredentials[accessKey=***, secretKey=***, sessionToken=***]";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
