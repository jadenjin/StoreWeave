package com.jdc.storeweave.provider.s3;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageClientContractTest extends AbstractStorageClientContract {

    private final S3CompatibleTestServer server = new S3CompatibleTestServer();

    @Override
    protected StorageClient createClient() {
        return new S3StorageProvider().create(new StorageConfiguration(
                "s3-contract",
                S3StorageProvider.TYPE,
                server.endpoint(),
                "us-east-1",
                StorageCredentials.of("test-access-key", "test-secret-key"),
                Map.of(S3StorageProvider.PATH_STYLE_ACCESS, "true")));
    }

    @AfterAll
    void stopServer() {
        server.close();
    }
}
