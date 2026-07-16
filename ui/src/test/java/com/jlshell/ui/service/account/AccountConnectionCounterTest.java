package com.jlshell.ui.service.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.SessionDescriptor;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.model.SessionState;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AccountConnectionCounterTest {

    @Test
    void countsOnlyActuallyConnectedSshSessions() {
        SessionManager sessions = new StubSessionManager(List.of(
                descriptor(SessionState.CONNECTED),
                descriptor(SessionState.CONNECTED),
                descriptor(SessionState.CONNECTING),
                descriptor(SessionState.FAILED),
                descriptor(SessionState.CLOSED)
        ));

        assertEquals(2, AccountConnectionCounter.connectedSessions(sessions));
    }

    private static SessionDescriptor descriptor(SessionState state) {
        return new SessionDescriptor(SessionId.randomId(), "test", null, state, Instant.now());
    }

    private record StubSessionManager(List<SessionDescriptor> descriptors) implements SessionManager {
        @Override
        public CompletableFuture<SshSession> openSession(ConnectionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SshSession> getSession(SessionId sessionId) {
            return Optional.empty();
        }

        @Override
        public List<SessionDescriptor> listSessions() {
            return descriptors;
        }

        @Override
        public CompletableFuture<Void> closeSession(SessionId sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> closeAll() {
            throw new UnsupportedOperationException();
        }
    }
}
