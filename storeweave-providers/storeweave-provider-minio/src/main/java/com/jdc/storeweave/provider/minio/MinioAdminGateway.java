package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.capability.ServerInfo;

interface MinioAdminGateway {

    void setQuotaBytes(String bucket, long bytes) throws Exception;

    long getQuotaBytes(String bucket) throws Exception;

    void clearQuota(String bucket) throws Exception;

    ServerInfo serverInfo() throws Exception;
}
