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
@ActiveProfiles("s3")
@TestPropertySource(properties = {
        "STOREWEAVE_S3_ENDPOINT=http://127.0.0.1:9000",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key"
})
class StoreWeaveS3ProfileTests {

    @Autowired
    StorageRegistry registry;

    @Autowired
    StorageClient primaryStorageClient;

    @Test
    void contextLoadsWithS3Provider() {
        assertThat(registry.providerTypes()).contains("local", "s3");
        assertThat(primaryStorageClient.name()).isEqualTo("s3");
        assertThat(primaryStorageClient.providerType()).isEqualTo("s3");
    }
}
