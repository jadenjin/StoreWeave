package com.jdc.storeweave.provider.sftp;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;

import java.net.URI;
import java.util.Locale;

public final class SftpStorageProvider implements StorageProvider {

    public static final String TYPE = "sftp";
    public static final String ROOT_DIRECTORY = "root-directory";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String SOCKET_TIMEOUT_MS = "socket-timeout-ms";
    public static final String STRICT_HOST_KEY_CHECKING = "strict-host-key-checking";
    public static final String KNOWN_HOSTS = "known-hosts";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("SFTP provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("SFTP endpoint is required"));
        validateEndpoint(endpoint);
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("SFTP username and password are required"));
        String root = normalizeRoot(configuration.option(ROOT_DIRECTORY).orElse("/"));
        int connectTimeout = intOption(configuration, CONNECT_TIMEOUT_MS, 5_000);
        int socketTimeout = intOption(configuration, SOCKET_TIMEOUT_MS, 30_000);
        boolean strict = booleanOption(configuration, STRICT_HOST_KEY_CHECKING, true);
        String knownHosts = configuration.option(KNOWN_HOSTS).filter(value -> !value.isBlank()).orElse(null);
        if (strict && knownHosts == null) {
            throw invalid("Option 'known-hosts' is required when strict-host-key-checking is true");
        }
        SftpConnectionFactory connections = new JschSftpConnectionFactory(
                endpoint.getHost(),
                endpoint.getPort() > 0 ? endpoint.getPort() : 22,
                credentials.accessKey(),
                credentials.secretKey(),
                connectTimeout,
                socketTimeout,
                strict,
                knownHosts);
        return new SftpStorageClient(configuration.name(), root, connections);
    }

    private static void validateEndpoint(URI endpoint) {
        if (!"sftp".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isBlank()
                && !"/".equals(endpoint.getPath()))) {
            throw invalid("SFTP endpoint must be sftp://host[:port] without credentials or a path");
        }
        if (endpoint.getPort() == 0 || endpoint.getPort() > 65_535) {
            throw invalid("SFTP endpoint port must be between 1 and 65535");
        }
    }

    private static String normalizeRoot(String value) {
        String normalized = value.replace('\\', '/').trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String segment : normalized.split("/")) {
            if (".".equals(segment) || "..".equals(segment) || hasControlCharacter(segment)) {
                throw invalid("SFTP root-directory contains an invalid path segment");
            }
        }
        return normalized;
    }

    private static int intOption(StorageConfiguration configuration, String name, int defaultValue) {
        return configuration.option(name).map(value -> {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw invalid("Option '" + name + "' must be a positive integer");
            }
        }).orElse(defaultValue);
    }

    private static boolean booleanOption(
            StorageConfiguration configuration, String name, boolean defaultValue) {
        return configuration.option(name).map(value -> {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw invalid("Option '" + name + "' must be true or false");
        }).orElse(defaultValue);
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static StorageException invalid(String message) {
        return new StorageException(StorageErrorCode.INVALID_REQUEST, message, TYPE, null, false);
    }
}
