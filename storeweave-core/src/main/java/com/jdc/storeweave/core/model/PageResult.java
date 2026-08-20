package com.jdc.storeweave.core.model;

import java.util.List;
import java.util.Optional;

public record PageResult<T>(List<T> items, String nextToken) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public Optional<String> nextTokenValue() {
        return Optional.ofNullable(nextToken);
    }

    public boolean hasNext() {
        return nextToken != null && !nextToken.isBlank();
    }
}
