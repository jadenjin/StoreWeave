package com.jdc.storeweave.provider.aliyun.oss;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.testkit.AbstractStorageClientContract;

class AliyunOssStorageClientContractTest extends AbstractStorageClientContract {

    @Override
    protected StorageClient createClient() {
        return new AliyunOssStorageClient("aliyun-contract", new InMemoryAliyunOss().client());
    }
}
