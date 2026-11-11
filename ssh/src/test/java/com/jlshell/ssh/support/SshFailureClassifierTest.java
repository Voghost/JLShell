package com.jlshell.ssh.support;

import com.jlshell.core.exception.ConnectionException.Reason;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.userauth.UserAuthException;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.nio.file.NoSuchFileException;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SshFailureClassifierTest {
    @Test void classifiesWrappedFailuresByCauseAndStage() {
        assertEquals(Reason.DNS, SshFailureClassifier.classify(new IOException(new UnknownHostException()), false));
        assertEquals(Reason.REFUSED, SshFailureClassifier.classify(new IOException(new ConnectException()), false));
        assertEquals(Reason.TIMEOUT, SshFailureClassifier.classify(new UserAuthException(new SocketTimeoutException()), true));
        assertEquals(Reason.UNREACHABLE, SshFailureClassifier.classify(new NoRouteToHostException(), false));
        assertEquals(Reason.CONNECTION_LOST, SshFailureClassifier.classify(new SocketException("Connection reset"), false));
        assertEquals(Reason.HOST_KEY, SshFailureClassifier.classify(new TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE), false));
        assertEquals(Reason.AUTHENTICATION, SshFailureClassifier.classify(new UserAuthException("denied"), true));
        assertEquals(Reason.KEY_FILE, SshFailureClassifier.classify(new IOException(new NoSuchFileException("key")), true));
        assertEquals(Reason.HANDSHAKE, SshFailureClassifier.classify(new TransportException("failed"), false));
        assertEquals(Reason.UNKNOWN, SshFailureClassifier.classify(new IOException("unknown"), false));
    }
}
