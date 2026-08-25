package com.jdc.storeweave.provider.ftp;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;

class FtpStorageClientContractTest extends AbstractStorageClientContract {

    @Override
    protected StorageClient createClient() {
        return new FtpStorageClient("ftp-contract", "/storage", new InMemoryFtpConnectionFactory());
    }
}
