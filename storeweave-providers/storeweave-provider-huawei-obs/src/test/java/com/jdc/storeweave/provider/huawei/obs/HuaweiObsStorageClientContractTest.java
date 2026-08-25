package com.jdc.storeweave.provider.huawei.obs;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;

class HuaweiObsStorageClientContractTest extends AbstractStorageClientContract {

    @Override
    protected StorageClient createClient() {
        return new HuaweiObsStorageClient("huawei-contract", "test-region", new InMemoryHuaweiObs());
    }
}
