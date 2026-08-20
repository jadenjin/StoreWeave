package com.jdc.storeweave.core.capability;

import java.net.URI;

public interface PresignOperations extends StorageCapability {

    URI presignGet(PresignRequest request);

    URI presignPut(PresignRequest request);
}
