package com.jdc.storeweave.core.spi;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

public final class StorageProviders {

    private StorageProviders() {
    }

    public static List<StorageProvider> load() {
        return load(Thread.currentThread().getContextClassLoader());
    }

    public static List<StorageProvider> load(ClassLoader classLoader) {
        ServiceLoader<StorageProvider> loader = ServiceLoader.load(StorageProvider.class, classLoader);
        return StreamSupport.stream(loader.spliterator(), false).toList();
    }
}
