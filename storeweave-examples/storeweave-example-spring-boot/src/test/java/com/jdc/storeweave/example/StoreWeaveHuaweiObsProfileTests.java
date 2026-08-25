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
@ActiveProfiles("huawei-obs")
@TestPropertySource(properties = {
        "STOREWEAVE_HUAWEI_OBS_ENDPOINT=https://obs.cn-south-1.myhuaweicloud.com",
        "HUAWEI_CLOUD_REGION=cn-south-1",
        "HUAWEI_CLOUD_ACCESS_KEY=test-access-key",
        "HUAWEI_CLOUD_SECRET_KEY=test-secret-key"
})
class StoreWeaveHuaweiObsProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithHuaweiObsProvider() {
        assertThat(registry.providerTypes()).contains(
                "local", "s3", "minio", "aliyun-oss", "tencent-cos", "huawei-obs");
        assertThat(primaryStorageClient.name()).isEqualTo("huawei");
        assertThat(primaryStorageClient.providerType()).isEqualTo("huawei-obs");
    }
}
