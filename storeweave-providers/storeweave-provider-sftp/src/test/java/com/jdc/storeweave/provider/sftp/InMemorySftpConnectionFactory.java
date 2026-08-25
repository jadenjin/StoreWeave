package com.jdc.storeweave.provider.sftp;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InMemorySftpConnectionFactory implements SftpConnectionFactory {

    private final Set<String> directories = new HashSet<>(Set.of("/"));
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, Instant> modified = new HashMap<>();
    private final Session session = new MemorySession();

    @Override
    public <T> T execute(Work<T> work) throws IOException {
        return work.apply(session);
    }

    @Override
    public InputStream openDownload(String path) throws IOException {
        byte[] content = files.get(path);
        if (content == null) {
            throw new FileNotFoundException(path);
        }
        return new ByteArrayInputStream(content);
    }

    private final class MemorySession implements Session {

        @Override
        public boolean directoryExists(String path) {
            return directories.contains(path);
        }

        @Override
        public boolean fileExists(String path) {
            return files.containsKey(path);
        }

        @Override
        public void createDirectories(String path) {
            String current = "";
            for (String segment : path.substring(1).split("/")) {
                if (!segment.isEmpty()) {
                    current += "/" + segment;
                    directories.add(current);
                    modified.putIfAbsent(current, Instant.now());
                }
            }
        }

        @Override
        public void deleteDirectory(String path) throws IOException {
            if (!directories.contains(path)) {
                throw new FileNotFoundException(path);
            }
            String descendant = path + "/";
            if (directories.stream().anyMatch(item -> item.startsWith(descendant))
                    || files.keySet().stream().anyMatch(item -> item.startsWith(descendant))) {
                throw new JschSftpConnectionFactory.SftpConflictException("Directory is not empty");
            }
            directories.remove(path);
            modified.remove(path);
        }

        @Override
        public void store(String path, InputStream content) throws IOException {
            if (!directories.contains(parent(path))) {
                throw new FileNotFoundException(parent(path));
            }
            files.put(path, content.readAllBytes());
            modified.put(path, Instant.now());
        }

        @Override
        public void deleteFile(String path) throws IOException {
            if (files.remove(path) == null) {
                throw new FileNotFoundException(path);
            }
            modified.remove(path);
        }

        @Override
        public void rename(String source, String target) throws IOException {
            byte[] content = files.remove(source);
            if (content == null) {
                throw new FileNotFoundException(source);
            }
            files.put(target, content);
            modified.remove(source);
            modified.put(target, Instant.now());
        }

        @Override
        public Entry stat(String path) {
            byte[] content = files.get(path);
            if (content != null) {
                return new Entry(name(path), path, false, content.length, modified.get(path));
            }
            if (directories.contains(path)) {
                return new Entry(name(path), path, true, 0, modified.get(path));
            }
            return null;
        }

        @Override
        public List<Entry> list(String path) throws IOException {
            if (!directories.contains(path)) {
                throw new FileNotFoundException(path);
            }
            List<Entry> result = new ArrayList<>();
            directories.stream()
                    .filter(item -> !item.equals(path) && parent(item).equals(path))
                    .map(this::stat)
                    .forEach(result::add);
            files.keySet().stream()
                    .filter(item -> parent(item).equals(path))
                    .map(this::stat)
                    .forEach(result::add);
            return result;
        }
    }

    private static String parent(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
