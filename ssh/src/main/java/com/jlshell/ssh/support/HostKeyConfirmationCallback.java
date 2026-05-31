package com.jlshell.ssh.support;

/**
 * Callback for interactive host key confirmation.
 * Decouples the SSH transport layer from UI-specific dialog code.
 */
@FunctionalInterface
public interface HostKeyConfirmationCallback {

    /**
     * @param hostname    remote host
     * @param port        remote port
     * @param keyType     key algorithm (e.g. "ssh-ed25519")
     * @param fingerprint human-readable fingerprint (e.g. "SHA256:wMn9S8uVjUX4...")
     * @param mismatch    true if the key differs from a previously known key
     * @return true to accept the key, false to reject
     */
    boolean confirm(String hostname, int port, String keyType, String fingerprint, boolean mismatch);
}
