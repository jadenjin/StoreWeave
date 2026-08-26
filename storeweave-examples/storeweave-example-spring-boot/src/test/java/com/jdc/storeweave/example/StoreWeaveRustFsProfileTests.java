package com.jdc.storeweave.example;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.registry.StorageRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("rustfs")
@TestPropertySource(properties = {
        "STOREWEAVE_RUSTFS_ENDPOINT=http://127.0.0.1:9000",
        "STOREWEAVE_RUSTFS_ACCESS_KEY=test-access-key",
        "STOREWEAVE_RUSTFS_SECRET_KEY=test-secret-key"
})
class StoreWeaveRustFsProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithRustFsProvider() {
        assertThat(registry.providerTypes()).contains("s3", "rustfs");
        assertThat(primaryStorageClient.name()).isEqualTo("rustfs");
        assertThat(primaryStorageClient.providerType()).isEqualTo("rustfs");
    }
}
