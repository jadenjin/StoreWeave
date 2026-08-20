package com.jdc.storeweave.core.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record StorageConfiguration(
        String name,
        String type,
        URI endpoint,
        String region,
        StorageCredentials credentials,
        Map<String, String> options) {

    public StorageConfiguration {
        name = requireText(name, "name");
        type = requireText(type, "type").toLowerCase(Locale.ROOT);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public Optional<URI> endpointValue() {
        return Optional.ofNullable(endpoint);
    }

    public Optional<String> regionValue() {
        return Optional.ofNullable(region).filter(value -> !value.isBlank());
    }

    public Optional<StorageCredentials> credentialsValue() {
        return Optional.ofNullable(credentials);
    }

    public Optional<String> option(String key) {
        return Optional.ofNullable(options.get(key)).filter(value -> !value.isBlank());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
