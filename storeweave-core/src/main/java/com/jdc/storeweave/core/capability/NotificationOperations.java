package com.jdc.storeweave.core.capability;

public interface NotificationOperations extends StorageCapability {

    void configureNotifications(String bucket, NotificationConfiguration configuration);

    void deleteNotifications(String bucket);
}
