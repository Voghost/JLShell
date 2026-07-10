package com.jlshell.ssh.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;

class SshjConnectionManagerTest {

    @Test
    void enablesTransportKeepAliveBeforeConnecting() {
        SSHClient client = new SSHClient();

        SshjConnectionManager.configureTransportKeepAlive(client);

        assertTrue(client.getConnection().getKeepAlive().isEnabled());
        assertEquals(30, client.getConnection().getKeepAlive().getKeepAliveInterval());
        assertEquals(90_000, client.getTimeout());
    }
}
