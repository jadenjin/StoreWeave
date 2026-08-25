package com.jdc.storeweave.provider.sftp;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;

class SftpStorageClientContractTest extends AbstractStorageClientContract {

    @Override
    protected StorageClient createClient() {
        return new SftpStorageClient("sftp-contract", "/storage", new InMemorySftpConnectionFactory());
    }
}
