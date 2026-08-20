package com.jdc.storeweave.core.capability;

import com.jdc.storeweave.core.model.StorageObject;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record PresignRequest(StorageObject object, Duration expiresIn, Map<String, String> headers) {

    public PresignRequest {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(expiresIn, "expiresIn");
        if (expiresIn.isNegative() || expiresIn.isZero()) {
            throw new IllegalArgumentException("expiresIn must be positive");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
