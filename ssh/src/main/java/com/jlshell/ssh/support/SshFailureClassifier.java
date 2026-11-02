package com.jlshell.ssh.support;

import com.jlshell.core.exception.ConnectionException.Reason;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.SSHException;
import net.schmizz.sshj.userauth.UserAuthException;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class SshFailureClassifier {
    private SshFailureClassifier() {}

    static Reason classify(Throwable error, boolean authenticating) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Reason ssh = Reason.UNKNOWN;
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof UnknownHostException) return Reason.DNS;
            if (current instanceof SocketTimeoutException) return Reason.TIMEOUT;
            if (current instanceof NoRouteToHostException) return Reason.UNREACHABLE;
            if (current instanceof ConnectException) return Reason.REFUSED;
            if (current instanceof SocketException) return Reason.CONNECTION_LOST;
            if (authenticating && (current instanceof NoSuchFileException || current instanceof FileNotFoundException
                    || current instanceof AccessDeniedException)) return Reason.KEY_FILE;
            if (current instanceof SSHException exception) {
                if (exception.getDisconnectReason() == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) return Reason.HOST_KEY;
                if (exception instanceof UserAuthException) ssh = Reason.AUTHENTICATION;
                else if (ssh == Reason.UNKNOWN) ssh = Reason.HANDSHAKE;
            } else if (current instanceof EOFException && ssh == Reason.UNKNOWN) ssh = Reason.HANDSHAKE;
        }
        return ssh;
    }
}
