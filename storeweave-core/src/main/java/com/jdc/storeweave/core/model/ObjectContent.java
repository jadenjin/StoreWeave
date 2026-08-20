package com.jdc.storeweave.core.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/** Repeatable content when its source can open a fresh stream for every call. */
public record ObjectContent(InputStreamSource source, long length, String contentType) {

    public ObjectContent {
        Objects.requireNonNull(source, "source");
        if (length < -1) {
            throw new IllegalArgumentException("length must be -1 (unknown) or non-negative");
        }
    }

    public InputStream openStream() throws IOException {
        return source.openStream();
    }

    public Optional<String> contentTypeValue() {
        return Optional.ofNullable(contentType).filter(value -> !value.isBlank());
    }
}
