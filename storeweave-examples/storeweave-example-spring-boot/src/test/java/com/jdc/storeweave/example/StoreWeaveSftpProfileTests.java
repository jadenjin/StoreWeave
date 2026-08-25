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
@ActiveProfiles("sftp")
@TestPropertySource(properties = {
        "STOREWEAVE_SFTP_ENDPOINT=sftp://127.0.0.1:22",
        "STOREWEAVE_SFTP_USERNAME=test-user",
        "STOREWEAVE_SFTP_PASSWORD=test-password",
        "STOREWEAVE_SFTP_STRICT_HOST_KEY_CHECKING=false"
})
class StoreWeaveSftpProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithSftpProvider() {
        assertThat(registry.providerTypes()).contains(
                "local", "s3", "minio", "aliyun-oss", "tencent-cos", "huawei-obs", "ftp", "sftp");
        assertThat(primaryStorageClient.name()).isEqualTo("sftp");
        assertThat(primaryStorageClient.providerType()).isEqualTo("sftp");
    }
}
