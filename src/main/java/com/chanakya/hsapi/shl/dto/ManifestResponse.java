package com.chanakya.hsapi.shl.dto;

import java.util.List;

public record ManifestResponse(
    String status,
    List<ManifestFile> files
) {
    public record ManifestFile(
        String contentType,
        String embedded
    ) {}
}
