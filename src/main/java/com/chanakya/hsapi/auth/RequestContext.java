package com.chanakya.hsapi.auth;

public final class RequestContext {

    public static final ScopedValue<String> CONSUMER_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> SOURCE = ScopedValue.newInstance();
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> ENTERPRISE_ID = ScopedValue.newInstance();

    private RequestContext() {}
}
