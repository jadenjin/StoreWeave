package com.jdc.storeweave.core.capability;

import java.util.List;

public interface BucketOperations extends StorageCapability {

    boolean bucketExists(String bucket);

    void createBucket(String bucket);

    void deleteBucket(String bucket);

    List<BucketInfo> listBuckets();
}
