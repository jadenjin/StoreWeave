package com.jdc.storeweave.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ObjectMetadata(
        StorageObject object,
        long size,
        String eTag,
        Instant lastModified,
        String contentType,
        Map<String, String> userMetadata) {

    public ObjectMetadata {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(lastModified, "lastModified");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }
}
