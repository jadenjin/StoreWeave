package com.jdc.storeweave.spring.boot;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.registry.StorageRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StoreWeaveAutoConfigurationTest {

    @TempDir
    Path root;

    @Test
    void configuresRegistryAndPrimaryClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StoreWeaveAutoConfiguration.class))
                .withPropertyValues(
                        "storeweave.primary=main",
                        "storeweave.stores.main.type=local",
                        "storeweave.stores.main.endpoint=" + root.toUri())
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageRegistry.class);
                    assertThat(context).hasSingleBean(StorageClient.class);
                    assertThat(context.getBean(StorageClient.class).name()).isEqualTo("main");
                });
    }
}
