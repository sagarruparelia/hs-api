package com.chanakya.hsapi.shl.model;

public enum ShlFlag {
    U,
    L;

    public boolean isSnapshot() {
        return this == U;
    }

    public boolean isLive() {
        return this == L;
    }

    public static boolean isValid(String flag) {
        if (flag == null || flag.isEmpty()) return false;
        try {
            valueOf(flag);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
