package com.jdc.storeweave.core.capability;

public record LifecycleRule(String id, String prefix, int expireAfterDays, boolean enabled) {

    public LifecycleRule {
        if (id == null || id.isBlank() || expireAfterDays < 1) {
            throw new IllegalArgumentException("Lifecycle rule requires an id and positive expiration days");
        }
        prefix = prefix == null ? "" : prefix;
    }
}
