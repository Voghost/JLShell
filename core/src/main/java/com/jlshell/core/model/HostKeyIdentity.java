package com.jlshell.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable host-key identity for an SSH target reached through one Link gateway. */
public record HostKeyIdentity(String gatewayId, String targetHost, int targetPort) {
    public HostKeyIdentity {
        if (gatewayId == null || gatewayId.isBlank() || gatewayId.length() > 256) {
            throw new IllegalArgumentException("gatewayId is required");
        }
        if (targetHost == null || targetHost.isBlank()) {
            throw new IllegalArgumentException("targetHost is required");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("targetPort is invalid");
        }
        gatewayId = gatewayId.strip();
        targetHost = targetHost.strip().toLowerCase(Locale.ROOT);
        if (targetHost.startsWith("[") && targetHost.endsWith("]")) {
            targetHost = targetHost.substring(1, targetHost.length() - 1);
        }
        if (targetHost.endsWith(".")) {
            targetHost = targetHost.substring(0, targetHost.length() - 1);
        }
        if (targetHost.isBlank()) throw new IllegalArgumentException("targetHost is required");
    }

    /** An isolated known_hosts name; direct SSH and another gateway never share this entry. */
    public String knownHostsName() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((gatewayId + "\0" + targetHost + "\0" + targetPort)
                    .getBytes(StandardCharsets.UTF_8));
            return "jlshell-link-" + HexFormat.of().formatHex(bytes, 0, 16) + ".invalid";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public String displayName() {
        return targetHost + ":" + targetPort + " via Link gateway " + gatewayId;
    }
}
