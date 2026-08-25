package com.jdc.storeweave.provider.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class CommonsNetFtpConnectionFactory implements FtpConnectionFactory {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final int connectTimeout;
    private final int socketTimeout;
    private final boolean passiveMode;

    CommonsNetFtpConnectionFactory(
            String host,
            int port,
            String username,
            String password,
            int connectTimeout,
            int socketTimeout,
            boolean passiveMode) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.connectTimeout = connectTimeout;
        this.socketTimeout = socketTimeout;
        this.passiveMode = passiveMode;
    }

    @Override
    public <T> T execute(Work<T> work) throws IOException {
        FTPClient client = connect();
        try {
            return work.apply(new CommonsNetSession(client));
        } finally {
            disconnect(client);
        }
    }

    @Override
    public InputStream openDownload(String path) throws IOException {
        FTPClient client = connect();
        InputStream content = client.retrieveFileStream(path);
        if (content == null) {
            disconnect(client);
            throw new java.io.FileNotFoundException(path);
        }
        return new FilterInputStream(content) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                IOException failure = null;
                try {
                    super.close();
                    if (!client.completePendingCommand()) {
                        failure = new IOException("FTP download did not complete: " + client.getReplyString());
                    }
                } catch (IOException exception) {
                    failure = exception;
                } finally {
                    disconnect(client);
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    private FTPClient connect() throws IOException {
        FTPClient client = new FTPClient();
        try {
            client.setControlEncoding("UTF-8");
            client.setConnectTimeout(connectTimeout);
            client.setDefaultTimeout(connectTimeout);
            client.connect(host, port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IOException("FTP connection rejected: " + client.getReplyString());
            }
            client.setSoTimeout(socketTimeout);
            client.setDataTimeout(Duration.ofMillis(socketTimeout));
            if (!client.login(username, password)) {
                throw new FtpAuthenticationException("FTP login failed");
            }
            if (passiveMode) {
                client.enterLocalPassiveMode();
            } else {
                client.enterLocalActiveMode();
            }
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IOException("FTP binary mode failed: " + client.getReplyString());
            }
            client.setBufferSize(1024 * 1024);
            return client;
        } catch (IOException | RuntimeException exception) {
            disconnect(client);
            throw exception;
        }
    }

    private static void disconnect(FTPClient client) {
        if (client == null || !client.isConnected()) {
            return;
        }
        try {
            client.logout();
        } catch (IOException ignored) {
            // Best effort during cleanup.
        }
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // Best effort during cleanup.
        }
    }

    private static final class CommonsNetSession implements Session {

        private final FTPClient client;

        private CommonsNetSession(FTPClient client) {
            this.client = client;
        }

        @Override
        public boolean directoryExists(String path) throws IOException {
            FTPFile file = client.mlistFile(path);
            if (file != null) {
                return file.isDirectory();
            }
            String original = client.printWorkingDirectory();
            boolean exists = client.changeWorkingDirectory(path);
            if (original != null) {
                client.changeWorkingDirectory(original);
            }
            return exists;
        }

        @Override
        public boolean fileExists(String path) throws IOException {
            FTPFile file = client.mlistFile(path);
            if (file != null) {
                return file.isFile();
            }
            FTPFile[] files = client.listFiles(path);
            return files.length == 1 && files[0].isFile();
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
                if (!directoryExists(current) && !client.makeDirectory(current)) {
                    throw failure("create directory", current);
                }
            }
        }

        @Override
        public void deleteDirectory(String path) throws IOException {
            if (!client.removeDirectory(path)) {
                throw new FtpConflictException("Cannot delete FTP directory: " + path);
            }
        }

        @Override
        public void store(String path, InputStream content) throws IOException {
            if (!client.storeFile(path, content)) {
                throw failure("store file", path);
            }
        }

        @Override
        public void deleteFile(String path) throws IOException {
            if (!client.deleteFile(path)) {
                throw failure("delete file", path);
            }
        }

        @Override
        public void rename(String source, String target) throws IOException {
            if (!client.rename(source, target)) {
                throw failure("rename file", source + " -> " + target);
            }
        }

        @Override
        public Entry stat(String path) throws IOException {
            FTPFile file = client.mlistFile(path);
            if (file == null) {
                FTPFile[] files = client.listFiles(path);
                if (files.length != 1) {
                    return null;
                }
                file = files[0];
            }
            return entry(path, file);
        }

        @Override
        public List<Entry> list(String path) throws IOException {
            FTPFile[] files = client.listFiles(path);
            List<Entry> result = new ArrayList<>(files.length);
            for (FTPFile file : files) {
                String name = file.getName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                result.add(entry(child(path, name), file));
            }
            return result;
        }

        private static Entry entry(String path, FTPFile file) {
            Instant modified = file.getTimestamp() == null
                    ? Instant.EPOCH : file.getTimestamp().toInstant();
            return new Entry(file.getName(), path, file.isDirectory(), file.getSize(), modified);
        }

        private IOException failure(String operation, String path) {
            return new IOException("FTP " + operation + " failed for " + path + ": " + client.getReplyString());
        }

        private static String child(String parent, String name) {
            return parent.endsWith("/") ? parent + name : parent + "/" + name;
        }
    }

    static final class FtpAuthenticationException extends IOException {
        FtpAuthenticationException(String message) {
            super(message);
        }
    }

    static final class FtpConflictException extends IOException {
        FtpConflictException(String message) {
            super(message);
        }
    }
}
