package com.jdc.storeweave.spring.boot;

import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("storeweave")
public class StoreWeaveProperties {

    private String primary;
    private Map<String, StoreProperties> stores = new LinkedHashMap<>();

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary;
    }

    public Map<String, StoreProperties> getStores() {
        return stores;
    }

    public void setStores(Map<String, StoreProperties> stores) {
        this.stores = stores == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stores);
    }

    public static class StoreProperties {

        private String type;
        private URI endpoint;
        private String region;
        private String accessKey;
        private String secretKey;
        private String sessionToken;
        private Map<String, String> options = new LinkedHashMap<>();

        public StorageConfiguration toConfiguration(String name) {
            StorageCredentials credentials = null;
            boolean hasAccessKey = accessKey != null && !accessKey.isBlank();
            boolean hasSecretKey = secretKey != null && !secretKey.isBlank();
            if (hasAccessKey != hasSecretKey) {
                throw new IllegalArgumentException(
                        "Both access-key and secret-key are required for store '" + name + "'");
            }
            if (hasAccessKey) {
                credentials = new StorageCredentials(accessKey, secretKey, sessionToken);
            }
            return new StorageConfiguration(name, type, endpoint, region, credentials, options);
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getSessionToken() {
            return sessionToken;
        }

        public void setSessionToken(String sessionToken) {
            this.sessionToken = sessionToken;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
        }
    }
}
