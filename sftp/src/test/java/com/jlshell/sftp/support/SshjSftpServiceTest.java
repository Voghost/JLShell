package com.jlshell.sftp.support;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.model.*;
import com.jlshell.sftp.service.TransferProgressListener;
import net.schmizz.sshj.SSHClient;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise the real SSHJ wire protocol against a loopback SFTP server. */
class SshjSftpServiceTest {
    @TempDir Path temporary;
    private SshServer server;
    private SSHClient client;
    private ExecutorService worker;
    private SshjSftpService service;
    private SshSession session;
    private Path remote;
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger opens = new AtomicInteger();
    private volatile int rejectAfter = Integer.MAX_VALUE;
    private volatile CountDownLatch blockedWrite;
    private volatile CountDownLatch releaseWrite;

    @BeforeEach void connect() throws Exception {
        remote = Files.createDirectory(temporary.resolve("remote"));
        var factory = new SftpSubsystemFactory.Builder().build();
        factory.addSftpEventListener(new SftpEventListener() {
            @Override public void open(ServerSession s, String handle, Handle file) { opens.incrementAndGet(); }
            @Override public void writing(ServerSession s, String handle, FileHandle file, long offset,
                                          byte[] data, int start, int length) throws IOException {
                if (writes.incrementAndGet() >= rejectAfter) throw new IOException("Injected disk write failure");
                CountDownLatch blocked = blockedWrite;
                if (blocked != null) {
                    blocked.countDown();
                    try {
                        if (!releaseWrite.await(10, TimeUnit.SECONDS)) throw new IOException("Write gate timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
            }
        });
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1"); server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temporary.resolve("host-key")));
        server.setPasswordAuthenticator((user, password, s) -> user.equals("test") && password.equals("test"));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remote));
        server.setSubsystemFactories(List.of(factory)); server.start();
        client = new SSHClient(); client.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.PromiscuousVerifier());
        client.connect("127.0.0.1", server.getPort()); client.authPassword("test", "test");
        session = mock(SshSession.class);
        when(session.unwrap(SSHClient.class)).thenReturn(Optional.of(client));
        worker = Executors.newSingleThreadExecutor(); service = new SshjSftpService(worker);
    }

    @AfterEach void close() throws Exception {
        if (releaseWrite != null) releaseWrite.countDown();
        if (worker != null) { worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS); }
        if (client != null) client.close();
        if (server != null) server.stop(true);
    }

    @Test void uploadsAcrossPacketBoundariesAndResumesWithoutChangingPrefix() throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024 + 37]; new Random(42).nextBytes(bytes);
        Path source = Files.write(temporary.resolve("source"), bytes);
        for (int offset : new int[]{0, 173_017, bytes.length, bytes.length + 10}) {
            Files.write(remote.resolve("result"), Arrays.copyOf(bytes, offset));
            var completed = new AtomicBoolean();
            service.upload(session, new TransferRequest(source, "/result", TransferResumeMode.RESUME_IF_POSSIBLE, 256 * 1024),
                    new TransferProgressListener() {
                        @Override public void onCompleted(TransferProgress p) {
                            try { assertArrayEquals(bytes, Files.readAllBytes(remote.resolve("result"))); }
                            catch (IOException e) { throw new AssertionError(e); }
                            completed.set(true);
                        }
                    }).get(15, TimeUnit.SECONDS);
            assertTrue(completed.get());
            assertArrayEquals(bytes, Files.readAllBytes(remote.resolve("result")));
        }
    }

    @Test void overwriteAndEmptyFileTruncateExistingContent() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[0], {1, 2, 3}}) {
            Files.write(remote.resolve("result"), new byte[100_000]);
            Path source = Files.write(temporary.resolve("source"), bytes);
            service.upload(session, new TransferRequest(source, "/result", TransferResumeMode.OVERWRITE, 0), null)
                    .get(10, TimeUnit.SECONDS);
            assertArrayEquals(bytes, Files.readAllBytes(remote.resolve("result")));
        }
    }

    @Test void cancellationDeletesPartialUploadAndDoesNotRetry() throws Exception {
        Path source = Files.write(temporary.resolve("source"), new byte[2 * 1024 * 1024]);
        var cancel = new AtomicBoolean(); var completed = new AtomicBoolean(); var failed = new AtomicInteger();
        var future = service.upload(session, new TransferRequest(source, "/cancelled", TransferResumeMode.OVERWRITE, 0),
                new TransferProgressListener() {
                    public boolean isCancelled() { return cancel.get(); }
                    public void onProgress(TransferProgress p) { if (p.transferredBytes() > 100_000) cancel.set(true); }
                    public void onCompleted(TransferProgress p) { completed.set(true); }
                    public void onFailed(TransferProgress p, Throwable error) { failed.incrementAndGet(); }
                });
        assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
        assertFalse(completed.get()); assertEquals(1, failed.get()); assertEquals(1, opens.get());
        assertFalse(Files.exists(remote.resolve("cancelled")));
    }

    @Test void rejectedWriteFailsInsteadOfSilentlyRestartingUpload() throws Exception {
        rejectAfter = 2;
        Path source = Files.write(temporary.resolve("source"), new byte[1024 * 1024]);
        var completed = new AtomicBoolean(); var failed = new AtomicInteger();
        var future = service.upload(session, new TransferRequest(source, "/rejected", TransferResumeMode.OVERWRITE, 0),
                new TransferProgressListener() {
                    public void onCompleted(TransferProgress p) { completed.set(true); }
                    public void onFailed(TransferProgress p, Throwable error) { failed.incrementAndGet(); }
                });
        assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
        assertFalse(completed.get()); assertEquals(1, failed.get()); assertEquals(1, opens.get());
    }

    @Test void completionWaitsForOutstandingServerAcknowledgements() throws Exception {
        Path source = Files.write(temporary.resolve("source"), new byte[200_000]);
        blockedWrite = new CountDownLatch(1); releaseWrite = new CountDownLatch(1);
        var allQueued = new CountDownLatch(1); var completed = new AtomicBoolean();
        var future = service.upload(session, new TransferRequest(source, "/gated", TransferResumeMode.OVERWRITE, 0),
                new TransferProgressListener() {
                    public void onProgress(TransferProgress p) {
                        if (p.transferredBytes() == p.totalBytes()) allQueued.countDown();
                    }
                    public void onCompleted(TransferProgress p) { completed.set(true); }
                });
        assertTrue(blockedWrite.await(5, TimeUnit.SECONDS));
        assertTrue(allQueued.await(5, TimeUnit.SECONDS), "Multiple packets must be queued without awaiting each ACK");
        assertFalse(future.isDone()); assertFalse(completed.get());
        releaseWrite.countDown(); future.get(10, TimeUnit.SECONDS);
        assertTrue(completed.get()); assertEquals(Files.size(source), Files.size(remote.resolve("gated")));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "jlshell.sftp.benchmark", matches = "true")
    void compareSynchronousAndPipelinedUploadsOverDelayedTransport() throws Exception {
        byte[] data = new byte[1024 * 1024]; new Random(87).nextBytes(data);
        Path source = Files.write(temporary.resolve("benchmark"), data);
        try (var proxy = new DelayedProxy(server.getPort(), 15);
             var delayed = new SSHClient()) {
            delayed.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.PromiscuousVerifier());
            delayed.connect("127.0.0.1", proxy.port()); delayed.authPassword("test", "test");
            long baselineStart = System.nanoTime();
            try (var sftp = delayed.newSFTPClient();
                 var file = sftp.open("/synchronous", EnumSet.of(net.schmizz.sshj.sftp.OpenMode.WRITE,
                         net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC))) {
                for (int pos = 0; pos < data.length; pos += 32700)
                    file.write(pos, data, pos, Math.min(32700, data.length - pos));
            }
            long baseline = System.nanoTime() - baselineStart;
            when(session.unwrap(SSHClient.class)).thenReturn(Optional.of(delayed));
            long pipelinedStart = System.nanoTime();
            service.upload(session, new TransferRequest(source, "/pipelined", TransferResumeMode.OVERWRITE, 0), null)
                    .get(30, TimeUnit.SECONDS);
            long pipelined = System.nanoTime() - pipelinedStart;
            assertArrayEquals(data, Files.readAllBytes(remote.resolve("pipelined")));
            assertArrayEquals(data, Files.readAllBytes(remote.resolve("synchronous")));
            System.out.printf("SFTP 1 MiB / 30 ms injected RTT: synchronous=%.1f ms, pipelined=%.1f ms, speedup=%.2fx%n",
                    baseline / 1e6, pipelined / 1e6, (double) baseline / pipelined);
        }
    }

    /** Delays packets in both directions without sleeping for each SFTP request. */
    private static final class DelayedProxy implements AutoCloseable {
        private final java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        private final ExecutorService pumps = Executors.newCachedThreadPool();
        private final ScheduledExecutorService upstream = Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService downstream = Executors.newSingleThreadScheduledExecutor();
        private volatile java.net.Socket incoming, outgoing;
        DelayedProxy(int serverPort, int delayMillis) throws IOException {
            pumps.submit(() -> {
                try {
                    incoming = listener.accept(); outgoing = new java.net.Socket("127.0.0.1", serverPort);
                    incoming.setTcpNoDelay(true); outgoing.setTcpNoDelay(true);
                    pumps.submit(() -> relay(incoming, outgoing, upstream, delayMillis));
                    relay(outgoing, incoming, downstream, delayMillis);
                } catch (IOException ignored) { /* Test cleanup closes the sockets. */ }
            });
        }
        int port() { return listener.getLocalPort(); }
        private void relay(java.net.Socket from, java.net.Socket to, ScheduledExecutorService queue, int delay) {
            try {
                byte[] buffer = new byte[65536]; int count;
                while ((count = from.getInputStream().read(buffer)) >= 0) {
                    byte[] packet = Arrays.copyOf(buffer, count);
                    queue.schedule(() -> {
                        try { to.getOutputStream().write(packet); to.getOutputStream().flush(); }
                        catch (IOException ignored) { /* Socket closed by test. */ }
                    }, delay, TimeUnit.MILLISECONDS);
                }
            } catch (IOException | RejectedExecutionException ignored) { /* Test cleanup. */ }
        }
        public void close() throws IOException {
            listener.close();
            if (incoming != null) incoming.close(); if (outgoing != null) outgoing.close();
            pumps.shutdownNow(); upstream.shutdownNow(); downstream.shutdownNow();
        }
    }

}
