package com.jdc.storeweave.core.capability;

import java.time.Instant;

public record BucketInfo(String name, Instant creationTime) {

    public BucketInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
