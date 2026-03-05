package com.chanakya.hsapi.shl.dto;

import com.chanakya.hsapi.shl.model.AccessRecord;

import java.util.List;

public record ShlAccessHistoryResponse(
    String hsid_uuid,
    List<AccessRecord> history
) {}
