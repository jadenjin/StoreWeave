package com.jdc.storeweave.provider.local;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

class LocalStorageClientContractTest extends AbstractStorageClientContract {

    @TempDir
    Path root;

    @Override
    protected StorageClient createClient() {
        return new LocalStorageProvider().create(new StorageConfiguration(
                "local-test",
                LocalStorageProvider.TYPE,
                root.toUri(),
                null,
                null,
                Map.of()));
    }
}
