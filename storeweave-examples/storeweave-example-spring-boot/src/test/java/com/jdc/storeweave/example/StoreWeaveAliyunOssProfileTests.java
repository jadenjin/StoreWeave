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
@ActiveProfiles("aliyun-oss")
@TestPropertySource(properties = {
        "STOREWEAVE_ALIYUN_OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com",
        "ALIBABA_CLOUD_ACCESS_KEY_ID=test-access-key",
        "ALIBABA_CLOUD_ACCESS_KEY_SECRET=test-secret-key"
})
class StoreWeaveAliyunOssProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithAliyunOssProvider() {
        assertThat(registry.providerTypes()).contains("local", "s3", "minio", "aliyun-oss");
        assertThat(primaryStorageClient.name()).isEqualTo("aliyun");
        assertThat(primaryStorageClient.providerType()).isEqualTo("aliyun-oss");
    }
}
