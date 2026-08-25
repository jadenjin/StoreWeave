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
@ActiveProfiles("tencent-cos")
@TestPropertySource(properties = {
        "TENCENT_CLOUD_REGION=ap-guangzhou",
        "TENCENT_CLOUD_SECRET_ID=test-secret-id",
        "TENCENT_CLOUD_SECRET_KEY=test-secret-key"
})
class StoreWeaveTencentCosProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithTencentCosProvider() {
        assertThat(registry.providerTypes())
                .contains("local", "s3", "minio", "aliyun-oss", "tencent-cos");
        assertThat(primaryStorageClient.name()).isEqualTo("tencent");
        assertThat(primaryStorageClient.providerType()).isEqualTo("tencent-cos");
    }
}
