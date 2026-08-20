package com.jdc.storeweave.core.model;

import java.util.Objects;
import java.util.Optional;

public record PutObjectResult(StorageObject object, String eTag, String versionId) {

    public PutObjectResult {
        Objects.requireNonNull(object, "object");
    }

    public Optional<String> versionIdValue() {
        return Optional.ofNullable(versionId);
    }
}
