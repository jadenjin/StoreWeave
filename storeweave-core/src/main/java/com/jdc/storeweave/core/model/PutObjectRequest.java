package com.jdc.storeweave.core.model;

import java.util.Map;
import java.util.Objects;

public record PutObjectRequest(StorageObject object, ObjectContent content, Map<String, String> metadata) {

    public PutObjectRequest {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(content, "content");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public PutObjectRequest(StorageObject object, ObjectContent content) {
        this(object, content, Map.of());
    }
}
