package com.jlshell.ssh.support;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;

import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extends OpenSSHKnownHosts to prompt the user when a host key is unknown or has changed.
 * Accepted keys are persisted to the known_hosts file.
 */
public class InteractiveHostKeyVerifier extends OpenSSHKnownHosts {

    private static final Logger log = LoggerFactory.getLogger(InteractiveHostKeyVerifier.class);

    private final HostKeyConfirmationCallback callback;

    public InteractiveHostKeyVerifier(File khFile, HostKeyConfirmationCallback callback) throws IOException {
        super(khFile);
        this.callback = callback;
    }

    @Override
    protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
        String fingerprint = SecurityUtils.getFingerprint(key);
        String keyType = KeyType.fromKey(key).toString();
        log.info("Host key for {} is unknown (type={}, fp={})", hostname, keyType, fingerprint);

        boolean accepted = callback.confirm(hostname, 22, keyType, fingerprint, false);
        if (accepted) {
            try {
                write(new HostEntry(null, hostname, KeyType.fromKey(key), key));
                log.info("Host key for {} accepted and saved to known_hosts", hostname);
            } catch (Exception e) {
                log.warn("Failed to save host key for {} to known_hosts: {}", hostname, e.getMessage());
            }
        }
        return accepted;
    }

    @Override
    protected boolean hostKeyChangedAction(String hostname, PublicKey key) {
        String fingerprint = SecurityUtils.getFingerprint(key);
        String keyType = KeyType.fromKey(key).toString();
        log.warn("Host key for {} has changed (type={}, fp={})", hostname, keyType, fingerprint);

        boolean accepted = callback.confirm(hostname, 22, keyType, fingerprint, true);
        if (accepted) {
            try {
                write(new HostEntry(null, hostname, KeyType.fromKey(key), key));
                log.info("Changed host key for {} accepted and saved to known_hosts", hostname);
            } catch (Exception e) {
                log.warn("Failed to save changed host key for {} to known_hosts: {}", hostname, e.getMessage());
            }
        }
        return accepted;
    }
}
