package com.chanakya.hsapi.shl.dto;

import java.util.Map;

public record ShlCountsResponse(
    Map<String, Integer> counts,
    int total
) {}
