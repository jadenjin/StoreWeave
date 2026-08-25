package com.jdc.storeweave.provider.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

interface FtpConnectionFactory extends AutoCloseable {

    <T> T execute(Work<T> work) throws IOException;

    InputStream openDownload(String path) throws IOException;

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Work<T> {
        T apply(Session session) throws IOException;
    }

    interface Session {
        boolean directoryExists(String path) throws IOException;

        boolean fileExists(String path) throws IOException;

        void createDirectories(String path) throws IOException;

        void deleteDirectory(String path) throws IOException;

        void store(String path, InputStream content) throws IOException;

        void deleteFile(String path) throws IOException;

        void rename(String source, String target) throws IOException;

        Entry stat(String path) throws IOException;

        List<Entry> list(String path) throws IOException;
    }

    record Entry(String name, String path, boolean directory, long size, Instant modified) {
    }
}
