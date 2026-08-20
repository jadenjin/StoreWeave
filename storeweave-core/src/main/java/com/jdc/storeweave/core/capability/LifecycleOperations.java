package com.jdc.storeweave.core.capability;

import java.util.List;

public interface LifecycleOperations extends StorageCapability {

    void putLifecycleRule(String bucket, LifecycleRule rule);

    List<LifecycleRule> listLifecycleRules(String bucket);

    void deleteLifecycleRule(String bucket, String ruleId);
}
