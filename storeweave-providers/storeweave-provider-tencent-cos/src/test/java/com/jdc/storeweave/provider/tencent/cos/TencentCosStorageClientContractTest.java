package com.jdc.storeweave.provider.tencent.cos;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;

class TencentCosStorageClientContractTest extends AbstractStorageClientContract {

    @Override
    protected StorageClient createClient() {
        return new TencentCosStorageClient("tencent-contract", new InMemoryTencentCos());
    }
}
