package com.jdc.storeweave.core.registry;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;
import com.jdc.storeweave.core.spi.StorageProviders;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Owns configured clients and closes them when they are replaced or removed. */
public final class StorageRegistry implements AutoCloseable {

    private final Map<String, StorageProvider> providers;
    private final Map<String, StorageClient> clients = new ConcurrentHashMap<>();

    public StorageRegistry(Collection<StorageProvider> providers) {
        Map<String, StorageProvider> indexed = new LinkedHashMap<>();
        for (StorageProvider provider : providers) {
            String type = normalize(provider.type());
            StorageProvider previous = indexed.putIfAbsent(type, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate storage provider type: " + type);
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public static StorageRegistry load() {
        return new StorageRegistry(StorageProviders.load());
    }

    public StorageClient register(StorageConfiguration configuration) {
        StorageProvider provider = Optional.ofNullable(providers.get(normalize(configuration.type())))
                .orElseThrow(() -> new StorageException(
                        StorageErrorCode.INVALID_REQUEST,
                        "No provider found for type: " + configuration.type()));
        StorageClient created = provider.create(configuration);
        StorageClient previous = clients.put(configuration.name(), created);
        closeQuietly(previous);
        return created;
    }

    public Optional<StorageClient> get(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    public StorageClient required(String name) {
        return get(name).orElseThrow(() -> new StorageException(
                StorageErrorCode.NOT_FOUND,
                "Storage client is not configured: " + name));
    }

    public Set<String> names() {
        return Set.copyOf(clients.keySet());
    }

    public void remove(String name) {
        closeQuietly(clients.remove(name));
    }

    public Set<String> providerTypes() {
        return providers.keySet();
    }

    @Override
    public void close() {
        clients.values().forEach(StorageRegistry::closeQuietly);
        clients.clear();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider type must not be blank");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void closeQuietly(StorageClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // Closing one client must not prevent registry cleanup.
            }
        }
    }
}
