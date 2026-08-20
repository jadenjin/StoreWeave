package com.jdc.storeweave.core.model;

public record ListObjectsRequest(String bucket, String prefix, String continuationToken, int pageSize) {

    public ListObjectsRequest {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        prefix = prefix == null ? "" : prefix.replace('\\', '/');
        if (pageSize < 1 || pageSize > 10_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 10000");
        }
    }

    public static ListObjectsRequest firstPage(String bucket, String prefix, int pageSize) {
        return new ListObjectsRequest(bucket, prefix, null, pageSize);
    }
}
