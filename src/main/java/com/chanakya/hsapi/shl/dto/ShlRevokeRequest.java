package com.chanakya.hsapi.shl.dto;

public record ShlRevokeRequest(
    String idType,
    String idValue,
    String linkId
) {}
