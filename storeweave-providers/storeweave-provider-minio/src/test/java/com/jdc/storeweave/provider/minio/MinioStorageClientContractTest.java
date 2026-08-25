package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;
import com.jdc.storeweave.testkit.s3.S3CompatibleTestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioStorageClientContractTest extends AbstractStorageClientContract {

    private final S3CompatibleTestServer server = new S3CompatibleTestServer();

    @Override
    protected StorageClient createClient() {
        return new MinioStorageProvider().create(new StorageConfiguration(
                "minio-contract",
                MinioStorageProvider.TYPE,
                server.endpoint(),
                "us-east-1",
                StorageCredentials.of("test-access-key", "test-secret-key"),
                Map.of()));
    }

    @AfterAll
    void stopServer() {
        server.close();
    }
}
