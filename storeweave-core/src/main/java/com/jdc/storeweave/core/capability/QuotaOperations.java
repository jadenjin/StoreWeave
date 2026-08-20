package com.jdc.storeweave.core.capability;

import java.util.OptionalLong;

public interface QuotaOperations extends StorageCapability {

    void setQuotaBytes(String bucket, long bytes);

    OptionalLong getQuotaBytes(String bucket);

    void clearQuota(String bucket);
}
