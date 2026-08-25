package com.jdc.storeweave.provider.ftp;

import com.jdc.storeweave.core.StorageClient;
import com.jdc.storeweave.core.config.StorageConfiguration;
import com.jdc.storeweave.core.config.StorageCredentials;
import com.jdc.storeweave.core.exception.StorageErrorCode;
import com.jdc.storeweave.core.exception.StorageException;
import com.jdc.storeweave.core.spi.StorageProvider;

import java.net.URI;
import java.util.Locale;

public final class FtpStorageProvider implements StorageProvider {

    public static final String TYPE = "ftp";
    public static final String ROOT_DIRECTORY = "root-directory";
    public static final String CONNECT_TIMEOUT_MS = "connect-timeout-ms";
    public static final String SOCKET_TIMEOUT_MS = "socket-timeout-ms";
    public static final String PASSIVE_MODE = "passive-mode";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StorageClient create(StorageConfiguration configuration) {
        if (!TYPE.equals(configuration.type().toLowerCase(Locale.ROOT))) {
            throw invalid("FTP provider cannot create storage type: " + configuration.type());
        }
        URI endpoint = configuration.endpointValue()
                .orElseThrow(() -> invalid("FTP endpoint is required"));
        validateEndpoint(endpoint);
        StorageCredentials credentials = configuration.credentialsValue()
                .orElseThrow(() -> invalid("FTP username and password are required"));
        String root = normalizeRoot(configuration.option(ROOT_DIRECTORY).orElse("/"));
        int connectTimeout = intOption(configuration, CONNECT_TIMEOUT_MS, 5_000);
        int socketTimeout = intOption(configuration, SOCKET_TIMEOUT_MS, 30_000);
        boolean passiveMode = booleanOption(configuration, PASSIVE_MODE, true);
        FtpConnectionFactory connections = new CommonsNetFtpConnectionFactory(
                endpoint.getHost(),
                endpoint.getPort() > 0 ? endpoint.getPort() : 21,
                credentials.accessKey(),
                credentials.secretKey(),
                connectTimeout,
                socketTimeout,
                passiveMode);
        return new FtpStorageClient(configuration.name(), root, connections);
    }

    private static void validateEndpoint(URI endpoint) {
        if (!"ftp".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isBlank()
                && !"/".equals(endpoint.getPath()))) {
            throw invalid("FTP endpoint must be ftp://host[:port] without credentials or a path");
        }
        if (endpoint.getPort() == 0 || endpoint.getPort() > 65_535) {
            throw invalid("FTP endpoint port must be between 1 and 65535");
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
                throw invalid("FTP root-directory contains an invalid path segment");
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
