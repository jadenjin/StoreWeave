package com.jdc.storeweave.core.model;

/** Identifies one object without leaking a vendor SDK type. */
public record StorageObject(String bucket, String key) {

    public StorageObject {
        bucket = requireText(bucket, "bucket");
        key = requireText(key, "key").replace('\\', '/');
        boolean hasInvalidSegment = java.util.Arrays.stream(key.split("/", -1))
                .anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
        if (key.startsWith("/") || key.endsWith("/") || hasInvalidSegment) {
            throw new IllegalArgumentException("Object key must be relative and must not traverse directories");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
