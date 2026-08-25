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
@ActiveProfiles("minio")
@TestPropertySource(properties = {
        "STOREWEAVE_MINIO_ENDPOINT=http://127.0.0.1:9000",
        "MINIO_ROOT_USER=test-access-key",
        "MINIO_ROOT_PASSWORD=test-secret-key"
})
class StoreWeaveMinioProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithMinioProvider() {
        assertThat(registry.providerTypes()).contains("local", "s3", "minio");
        assertThat(primaryStorageClient.name()).isEqualTo("minio");
        assertThat(primaryStorageClient.providerType()).isEqualTo("minio");
    }
}
