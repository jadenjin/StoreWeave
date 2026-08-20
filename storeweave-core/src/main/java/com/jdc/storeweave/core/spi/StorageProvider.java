package com.jdc.storeweave.core.spi;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;

public interface StorageProvider {

    String type();

    StorageClient create(StorageConfiguration configuration);
}
