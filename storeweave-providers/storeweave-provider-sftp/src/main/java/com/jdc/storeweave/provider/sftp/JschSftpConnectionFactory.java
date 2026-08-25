package com.jdc.storeweave.provider.sftp;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JschSftpConnectionFactory implements SftpConnectionFactory {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int connectTimeout;
    private final int socketTimeout;
    private final boolean strictHostKeyChecking;
    private final String knownHosts;

    JschSftpConnectionFactory(
            String host,
            int port,
            String username,
            String password,
            int connectTimeout,
            int socketTimeout,
            boolean strictHostKeyChecking,
            String knownHosts) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
        this.strictHostKeyChecking = strictHostKeyChecking;
        this.knownHosts = knownHosts;
    }

    @Override
    public <T> T execute(Work<T> work) throws IOException {
        Connection connection = connect();
        try {
            return work.apply(new JschSession(connection.channel()));
        } finally {
            connection.close();
        }
    }

    @Override
    public InputStream openDownload(String path) throws IOException {
        Connection connection = connect();
        try {
            InputStream content = connection.channel().get(path);
            return new FilterInputStream(content) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        connection.close();
                    }
                }
            };
        } catch (SftpException exception) {
            connection.close();
            throw translate(exception, path);
        } catch (RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    private Connection connect() throws IOException {
        com.jcraft.jsch.Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();
            if (strictHostKeyChecking) {
                jsch.setKnownHosts(knownHosts);
            }
            session = jsch.getSession(username, host, port);
            session.setPassword(password.getBytes(StandardCharsets.UTF_8));
            session.setConfig("StrictHostKeyChecking", strictHostKeyChecking ? "yes" : "no");
            session.setTimeout(socketTimeout);
            session.connect(connectTimeout);
            Channel opened = session.openChannel("sftp");
            opened.connect(connectTimeout);
            channel = (ChannelSftp) opened;
            return new Connection(session, channel);
        } catch (JSchException exception) {
            disconnect(channel, session);
            String message = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("auth fail") || message.contains("userauth")) {
                throw new SftpAuthenticationException("SFTP authentication failed", exception);
            }
            throw new IOException("SFTP connection failed", exception);
        } catch (RuntimeException exception) {
            disconnect(channel, session);
            throw exception;
        }
    }

    private static final class JschSession implements Session {

        private final ChannelSftp channel;

        private JschSession(ChannelSftp channel) {
            this.channel = channel;
        }

        @Override
        public boolean directoryExists(String path) throws IOException {
            SftpATTRS attributes = attributes(path);
            return attributes != null && attributes.isDir();
        }

        @Override
        public boolean fileExists(String path) throws IOException {
            SftpATTRS attributes = attributes(path);
            return attributes != null && !attributes.isDir();
        }

        @Override
        public void createDirectories(String path) throws IOException {
            if ("/".equals(path) || path.isBlank()) {
                return;
            }
            String current = path.startsWith("/") ? "/" : "";
            for (String segment : path.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                current = current.endsWith("/") ? current + segment : current + "/" + segment;
                SftpATTRS attributes = attributes(current);
                if (attributes == null) {
                    String directory = current;
                    invoke(() -> channel.mkdir(directory), directory);
                } else if (!attributes.isDir()) {
                    throw new SftpConflictException("SFTP path is not a directory: " + current);
                }
            }
        }

        @Override
        public void deleteDirectory(String path) throws IOException {
            try {
                channel.rmdir(path);
            } catch (SftpException exception) {
                if (exception.id == ChannelSftp.SSH_FX_FAILURE) {
                    throw new SftpConflictException("Cannot delete non-empty SFTP directory: " + path, exception);
                }
                throw translate(exception, path);
            }
        }

        @Override
        public void store(String path, InputStream content) throws IOException {
            invoke(() -> channel.put(content, path), path);
        }

        @Override
        public void deleteFile(String path) throws IOException {
            invoke(() -> channel.rm(path), path);
        }

        @Override
        public void rename(String source, String target) throws IOException {
            invoke(() -> channel.rename(source, target), source + " -> " + target);
        }

        @Override
        public Entry stat(String path) throws IOException {
            SftpATTRS attributes = attributes(path);
            if (attributes == null) {
                return null;
            }
            return entry(name(path), path, attributes);
        }

        @Override
        public List<Entry> list(String path) throws IOException {
            try {
                List<Entry> result = new ArrayList<>();
                for (ChannelSftp.LsEntry item : channel.ls(path)) {
                    if (".".equals(item.getFilename()) || "..".equals(item.getFilename())) {
                        continue;
                    }
                    result.add(entry(item.getFilename(), child(path, item.getFilename()), item.getAttrs()));
                }
                return result;
            } catch (SftpException exception) {
                throw translate(exception, path);
            }
        }

        private SftpATTRS attributes(String path) throws IOException {
            try {
                return channel.lstat(path);
            } catch (SftpException exception) {
                if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return null;
                }
                throw translate(exception, path);
            }
        }

        private static Entry entry(String name, String path, SftpATTRS attributes) {
            return new Entry(name, path, attributes.isDir(), attributes.getSize(),
                    Instant.ofEpochSecond(Integer.toUnsignedLong(attributes.getMTime())));
        }

        private static String name(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        private static String child(String parent, String name) {
            return parent.endsWith("/") ? parent + name : parent + "/" + name;
        }

        private static void invoke(SftpAction action, String path) throws IOException {
            try {
                action.run();
            } catch (SftpException exception) {
                throw translate(exception, path);
            }
        }
    }

    private static IOException translate(SftpException exception, String path) {
        if (exception.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            FileNotFoundException missing = new FileNotFoundException(path);
            missing.initCause(exception);
            return missing;
        }
        if (exception.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new SftpAuthorizationException("SFTP permission denied: " + path, exception);
        }
        return new IOException("SFTP operation failed for " + path, exception);
    }

    private static void disconnect(ChannelSftp channel, com.jcraft.jsch.Session session) {
        if (channel != null) {
            channel.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
    }

    private record Connection(com.jcraft.jsch.Session session, ChannelSftp channel) {
        void close() {
            disconnect(channel, session);
        }
    }

    @FunctionalInterface
    private interface SftpAction {
        void run() throws SftpException;
    }

    static final class SftpAuthenticationException extends IOException {
        SftpAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class SftpAuthorizationException extends IOException {
        SftpAuthorizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class SftpConflictException extends IOException {
        SftpConflictException(String message) {
            super(message);
        }

        SftpConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
