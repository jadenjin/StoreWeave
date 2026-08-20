package com.jdc.storeweave.core.capability;

import java.util.Map;

public record ServerInfo(String version, Map<String, String> attributes) {

    public ServerInfo {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
