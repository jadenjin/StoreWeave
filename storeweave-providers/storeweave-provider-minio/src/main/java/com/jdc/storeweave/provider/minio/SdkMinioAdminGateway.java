package com.jdc.storeweave.provider.minio;

import com.jdc.storeweave.core.capability.ServerInfo;
import io.minio.admin.MinioAdminClient;
import io.minio.admin.QuotaUnit;
import io.minio.admin.messages.info.Message;

import java.util.LinkedHashMap;
import java.util.Map;

final class SdkMinioAdminGateway implements MinioAdminGateway {

    private static final long KIBIBYTE = 1024L;

    private final MinioAdminClient client;

    SdkMinioAdminGateway(MinioAdminClient client) {
        this.client = client;
    }

    @Override
    public void setQuotaBytes(String bucket, long bytes) throws Exception {
        if (bytes < KIBIBYTE || bytes % KIBIBYTE != 0) {
            throw new IllegalArgumentException("MinIO quota must be a positive multiple of 1024 bytes");
        }
        client.setBucketQuota(bucket, bytes / KIBIBYTE, QuotaUnit.KB);
    }

    @Override
    public long getQuotaBytes(String bucket) throws Exception {
        return client.getBucketQuota(bucket);
    }

    @Override
    public void clearQuota(String bucket) throws Exception {
        client.clearBucketQuota(bucket);
    }

    @Override
    public ServerInfo serverInfo() throws Exception {
        Message message = client.getServerInfo();
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "deploymentId", message.deploymentID());
        put(attributes, "mode", message.mode());
        if (message.backend() != null) {
            put(attributes, "backend", message.backend().backendType());
            put(attributes, "onlineDisks", message.backend().onlineDisks());
            put(attributes, "offlineDisks", message.backend().offlineDisks());
        }
        if (message.buckets() != null) {
            put(attributes, "buckets", message.buckets().count());
        }
        if (message.objects() != null) {
            put(attributes, "objects", message.objects().count());
        }
        if (message.usage() != null) {
            put(attributes, "usageBytes", message.usage().size());
        }
        int serverCount = message.servers() == null ? 0 : message.servers().size();
        put(attributes, "servers", serverCount);
        String version = message.servers() == null
                ? null
                : message.servers().stream()
                        .map(server -> server.version())
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElse(null);
        return new ServerInfo(version, attributes);
    }

    private static void put(Map<String, String> target, String key, Object value) {
        if (value != null) {
            target.put(key, String.valueOf(value));
        }
    }
}
