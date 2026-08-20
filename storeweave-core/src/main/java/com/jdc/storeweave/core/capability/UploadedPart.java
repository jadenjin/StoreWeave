package com.jdc.storeweave.core.capability;

public record UploadedPart(int partNumber, String eTag, long size) {

    public UploadedPart {
        if (partNumber < 1 || size < 0) {
            throw new IllegalArgumentException("Invalid multipart part");
        }
    }
}
