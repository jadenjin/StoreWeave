package com.jdc.storeweave.spring.boot;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.registry.StorageRegistry;
import com.jdc.storeweave.core.spi.StorageProvider;
import com.jdc.storeweave.core.spi.StorageProviders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(StorageRegistry.class)
@EnableConfigurationProperties(StoreWeaveProperties.class)
public class StoreWeaveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    StorageRegistry storageRegistry(
            StoreWeaveProperties properties,
            ObjectProvider<StorageProvider> customProviders) {
        Map<String, StorageProvider> providers = new LinkedHashMap<>();
        StorageProviders.load().forEach(provider -> providers.put(normalize(provider.type()), provider));
        customProviders.orderedStream().forEach(provider -> providers.put(normalize(provider.type()), provider));

        StorageRegistry registry = new StorageRegistry(providers.values());
        try {
            properties.getStores().forEach((name, store) -> registry.register(store.toConfiguration(name)));
            return registry;
        } catch (RuntimeException exception) {
            registry.close();
            throw exception;
        }
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(StorageClient.class)
    @ConditionalOnProperty(prefix = "storeweave", name = "primary")
    StorageClient primaryStorageClient(StorageRegistry registry, StoreWeaveProperties properties) {
        return registry.required(properties.getPrimary());
    }

    private static String normalize(String type) {
        return type.toLowerCase(Locale.ROOT);
    }
}
