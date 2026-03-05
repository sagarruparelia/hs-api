package com.chanakya.hsapi.common.exception;

import java.time.Instant;

public record ErrorResponse(
    String error,
    String message,
    Instant timestamp,
    String path
) {
    public static ErrorResponse of(String error, String message, String path) {
        return new ErrorResponse(error, message, Instant.now(), path);
    }
}
