package com.jlshell.core.exception;

public class ConnectionException extends CoreException {
    public enum Reason { UNKNOWN, DNS, REFUSED, TIMEOUT, UNREACHABLE, CONNECTION_LOST, HOST_KEY, AUTHENTICATION, KEY_FILE, HANDSHAKE }
    private final Reason reason;

    public ConnectionException(String message) {
        super(message);
        reason = Reason.UNKNOWN;
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
        reason = Reason.UNKNOWN;
    }

    public ConnectionException(String message, Throwable cause, Reason reason) {
        super(message, cause);
        this.reason = reason == null ? Reason.UNKNOWN : reason;
    }

    public Reason reason() { return reason; }
}
