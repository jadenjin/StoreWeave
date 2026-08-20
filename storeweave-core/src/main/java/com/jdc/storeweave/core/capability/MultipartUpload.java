package com.jdc.storeweave.core.capability;

import com.jdc.storeweave.core.model.StorageObject;

import java.util.Objects;

public record MultipartUpload(String uploadId, StorageObject object) {

    public MultipartUpload {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
        Objects.requireNonNull(object, "object");
    }
}
