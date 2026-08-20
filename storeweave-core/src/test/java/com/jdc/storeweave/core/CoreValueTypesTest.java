package com.jdc.storeweave.core;

import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.model.StorageObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreValueTypesTest {

    @Test
    void rejectsAmbiguousOrTraversingObjectKeys() {
        assertThrows(IllegalArgumentException.class, () -> new StorageObject("bucket", "../secret"));
        assertThrows(IllegalArgumentException.class, () -> new StorageObject("bucket", "dir/../secret"));
        assertThrows(IllegalArgumentException.class, () -> new StorageObject("bucket", "dir//file"));
        assertThrows(IllegalArgumentException.class, () -> new StorageObject("bucket", "dir/"));
    }

    @Test
    void redactsEveryCredentialValue() {
        String rendered = new StorageCredentials("access-value", "secret-value", "token-value").toString();

        assertFalse(rendered.contains("access-value"));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("token-value"));
    }
}
