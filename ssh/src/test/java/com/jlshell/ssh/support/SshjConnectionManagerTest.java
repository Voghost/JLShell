package com.jlshell.ssh.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;

class SshjConnectionManagerTest {

    @Test
    void configuresSocketTimeoutWithoutStartingKeepAliveBeforeConnecting() {
        SSHClient client = new SSHClient();

        SshjConnectionManager.configureSocketTimeout(client);

        assertFalse(client.getConnection().getKeepAlive().isEnabled());
        assertEquals(Thread.State.NEW, client.getConnection().getKeepAlive().getState());
        assertEquals(90_000, client.getTimeout());
    }
}
