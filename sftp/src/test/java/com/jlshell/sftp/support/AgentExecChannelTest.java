package com.jlshell.sftp.support;

import com.jlshell.core.model.*;
import com.jlshell.ssh.support.session.SshjSession;
import net.schmizz.sshj.SSHClient;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise independent exec over actual SSHJ wire channels without touching user keys or servers. */
class AgentExecChannelTest {
    @TempDir Path directory;
    @Test void streamsBothLargeOutputsAndCancelsOnlyItsOwnChannel() throws Exception {
        var server=SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(directory.resolve("host-key")));
        server.setPasswordAuthenticator((user,password,session) -> user.equals("test") && password.equals("test"));
        server.setCommandFactory((channel,command) -> new AbstractCommandSupport(command,null) {
            public void run() {
                try {
                    if (command.equals("wait")) Thread.sleep(30000);
                    else {
                        byte[] bytes=new byte[8192]; java.util.Arrays.fill(bytes,(byte)'x');
                        for (int index=0; index<100; index++) { getOutputStream().write(bytes); getErrorStream().write(bytes); }
                        getOutputStream().flush(); getErrorStream().flush();
                    }
                    onExit(0);
                } catch (Exception error) { onExit(130); }
            }
        });
        server.start();
        try (var executor=Executors.newVirtualThreadPerTaskExecutor(); var client=new SSHClient()) {
            client.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.PromiscuousVerifier());
            client.connect("127.0.0.1",server.getPort()); client.authPassword("test","test");
            var session=new SshjSession(SessionId.randomId(),"Loopback",new ConnectionTarget("127.0.0.1",server.getPort(),"test",null,null),client,executor);
            try (var channel=session.startCommand(new CommandRequest("large",Duration.ofSeconds(10),false,Map.of())).get(5,TimeUnit.SECONDS)) {
                var stdout=CompletableFuture.supplyAsync(() -> read(channel.stdout()),executor);
                var stderr=CompletableFuture.supplyAsync(() -> read(channel.stderr()),executor);
                assertEquals(0,channel.completion().get(10,TimeUnit.SECONDS));
                assertEquals(819200,stdout.get(5,TimeUnit.SECONDS));
                assertEquals(819200,stderr.get(5,TimeUnit.SECONDS));
            }
            var waiting=session.startCommand(new CommandRequest("wait",Duration.ofSeconds(10),false,Map.of())).get(5,TimeUnit.SECONDS);
            waiting.cancel();
            waiting.completion().handle((value,error) -> null).get(5,TimeUnit.SECONDS);
            assertTrue(client.isConnected());
            try (var another=session.startCommand(new CommandRequest("large",Duration.ofSeconds(10),false,Map.of())).get(5,TimeUnit.SECONDS)) {
                var readers=CompletableFuture.allOf(CompletableFuture.runAsync(() -> read(another.stdout()),executor),CompletableFuture.runAsync(() -> read(another.stderr()),executor));
                assertEquals(0,another.completion().get(10,TimeUnit.SECONDS));
                readers.get(5,TimeUnit.SECONDS);
            }
        } finally { server.stop(true); }
    }
    private static int read(java.io.InputStream input) {
        try (input) { return input.readAllBytes().length; }
        catch (java.io.IOException error) { throw new CompletionException(error); }
    }
}
