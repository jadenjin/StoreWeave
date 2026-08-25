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
@ActiveProfiles("ftp")
@TestPropertySource(properties = {
        "STOREWEAVE_FTP_ENDPOINT=ftp://127.0.0.1:21",
        "STOREWEAVE_FTP_USERNAME=test-user",
        "STOREWEAVE_FTP_PASSWORD=test-password"
})
class StoreWeaveFtpProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithFtpProvider() {
        assertThat(registry.providerTypes()).contains(
                "local", "s3", "minio", "aliyun-oss", "tencent-cos", "huawei-obs", "ftp");
        assertThat(primaryStorageClient.name()).isEqualTo("ftp");
        assertThat(primaryStorageClient.providerType()).isEqualTo("ftp");
    }
}
