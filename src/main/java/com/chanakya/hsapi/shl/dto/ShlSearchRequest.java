package com.chanakya.hsapi.shl.dto;

public record ShlSearchRequest(
    String idType,
    String idValue,
    String hsid_uuid
) {}
