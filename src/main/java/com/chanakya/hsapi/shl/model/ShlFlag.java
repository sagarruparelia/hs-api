package com.chanakya.hsapi.shl.model;

public final class ShlFlag {

    public static final String U = "U";
    public static final String L = "L";

    private ShlFlag() {}

    public static boolean isValid(String flag) {
        if (flag == null || flag.isEmpty()) return false;
        return flag.equals(U) || flag.equals(L);
    }

    public static boolean isSnapshot(String flag) {
        return U.equals(flag);
    }

    public static boolean isLive(String flag) {
        return L.equals(flag);
    }
}
