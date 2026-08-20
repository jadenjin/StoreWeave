package com.jdc.storeweave.core.capability;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

public record NotificationConfiguration(URI destination, Set<StorageEventType> events) {

    public NotificationConfiguration {
        Objects.requireNonNull(destination, "destination");
        events = Set.copyOf(Objects.requireNonNull(events, "events"));
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
    }
}
