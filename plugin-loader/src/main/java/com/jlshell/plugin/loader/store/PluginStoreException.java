package com.jlshell.plugin.loader.store;

import java.net.URI;

public class PluginStoreException extends RuntimeException {
    private final int statusCode;
    private final URI uri;

    PluginStoreException(String message, int statusCode, URI uri) {
        super(message);
        this.statusCode = statusCode;
        this.uri = uri;
    }

    PluginStoreException(String message, Throwable cause) {
        this(message, cause, null);
    }

    PluginStoreException(String message, Throwable cause, URI uri) {
        super(message, cause);
        this.statusCode = 0;
        this.uri = uri;
    }

    public int statusCode() { return statusCode; }
    public URI uri() { return uri; }
}
