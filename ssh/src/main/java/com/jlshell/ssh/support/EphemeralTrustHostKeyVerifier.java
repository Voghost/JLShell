package com.jlshell.ssh.support;

import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程级 Accept-Once 策略。
 * 同一目标主机第一次出现的 Host Key 会被接受，后续若指纹变化则拒绝。
 */
public class EphemeralTrustHostKeyVerifier implements HostKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(EphemeralTrustHostKeyVerifier.class);

    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> algorithms = new ConcurrentHashMap<>();

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
        String endpoint = hostname + ":" + port;
        String fingerprint = Base64.getEncoder().encodeToString(key.getEncoded());
        String knownFingerprint = fingerprints.putIfAbsent(endpoint, fingerprint);
        algorithms.putIfAbsent(endpoint, key.getAlgorithm());

        if (knownFingerprint == null) {
            log.warn("Host key for {} accepted for current application session only", endpoint);
            return true;
        }

        return knownFingerprint.equals(fingerprint);
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
        String algorithm = algorithms.get(hostname + ":" + port);
        return algorithm == null ? List.of() : List.of(algorithm);
    }
}
