package com.jdc.storeweave.core.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ObjectContents {

    private ObjectContents() {
    }

    public static ObjectContent fromBytes(byte[] value, String contentType) {
        byte[] copy = Arrays.copyOf(Objects.requireNonNull(value, "value"), value.length);
        return new ObjectContent(() -> new ByteArrayInputStream(copy), copy.length, contentType);
    }

    public static ObjectContent fromString(String value, Charset charset, String contentType) {
        return fromBytes(Objects.requireNonNull(value, "value").getBytes(charset), contentType);
    }

    public static ObjectContent fromPath(Path path, String contentType) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return new ObjectContent(() -> Files.newInputStream(normalized), Files.size(normalized), contentType);
    }

    /** Creates one-shot content. The stream is owned and closed by the provider after put. */
    public static ObjectContent fromInputStream(InputStream input, long length, String contentType) {
        Objects.requireNonNull(input, "input");
        AtomicBoolean opened = new AtomicBoolean();
        return new ObjectContent(() -> {
            if (!opened.compareAndSet(false, true)) {
                throw new IOException("This object content stream has already been opened");
            }
            return input;
        }, length, contentType);
    }
}
