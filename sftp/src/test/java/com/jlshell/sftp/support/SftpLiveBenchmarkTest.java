package com.jlshell.sftp.support;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.model.TransferRequest;
import com.jlshell.sftp.model.TransferResumeMode;
import com.jlshell.sftp.service.FileChecksums;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in: transfers generated random data through the application's actual SFTP implementation. */
@EnabledIfSystemProperty(named = "jlshell.sftp.live", matches = "true")
class SftpLiveBenchmarkTest {
    @TempDir Path temporary;

    @Test void comparePublishedAndTunedUploadPipelines() throws Exception {
        String host = System.getProperty("jlshell.sftp.live.host", "");
        assertFalse(host.isBlank(), "Set jlshell.sftp.live.host explicitly");
        int port = Integer.getInteger("jlshell.sftp.live.port", 22);
        String user = System.getProperty("jlshell.sftp.live.user", "root");
        Path knownHosts = Path.of(System.getProperty("jlshell.sftp.live.knownHosts",
                Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString()));
        assertTrue(Files.isRegularFile(knownHosts), "A verified known_hosts file is required");
        int sizeMiB = Math.max(1, Math.min(128, Integer.getInteger("jlshell.sftp.live.sizeMiB", 16)));
        Path local = temporary.resolve("random-data.bin");
        byte[] chunk = new byte[64 * 1024]; var random = new Random(1881);
        try (var output = Files.newOutputStream(local)) {
            for (int index = 0; index < sizeMiB * 16; index++) { random.nextBytes(chunk); output.write(chunk); }
        }
        String expected = FileChecksums.localMd5(local, () -> false);
        var worker = Executors.newSingleThreadExecutor();
        String directory = "/tmp/jlshell-sftp-probe-" + UUID.randomUUID();
        List<String> created = new ArrayList<>();
        boolean ownsDirectory = false;
        try (var ssh = new SSHClient()) {
            ssh.loadKnownHosts(knownHosts.toFile());
            ssh.setConnectTimeout(10_000); ssh.setTimeout(90_000);
            long connecting = System.nanoTime(); ssh.connect(host, port);
            String key = System.getProperty("jlshell.sftp.live.key", "");
            if (key.isBlank()) ssh.authPublickey(user); else ssh.authPublickey(user, key);
            ssh.getConnection().setWindowSize(2 * 1024 * 1024);
            System.out.printf(Locale.ROOT, "SFTP_LIVE_CONNECTED seconds=%.3f%n", (System.nanoTime() - connecting) / 1e9);
            var context = mock(SshSession.class);
            when(context.unwrap(SSHClient.class)).thenReturn(Optional.of(ssh));
            try (var sftp = ssh.newSFTPClient()) {
                sftp.mkdir(directory); ownsDirectory = true;
            }
            try {
                // Warm both paths, then measure in both orders to reduce JIT/order bias.
                int[][] orders = {{16, 64}, {16, 64}, {64, 16}};
                for (int round = 0; round < orders.length; round++) for (int depth : orders[round]) {
                    var service = new SshjSftpService(worker, depth);
                    String destination = directory + "/depth-" + depth + ".bin";
                    if (!created.contains(destination)) created.add(destination);
                    long start = System.nanoTime();
                    service.upload(context, new TransferRequest(local, destination, TransferResumeMode.OVERWRITE, 64 * 1024), null)
                            .get(180, TimeUnit.SECONDS);
                    double seconds = (System.nanoTime() - start) / 1e9;
                    String actual = service.md5(context, destination, () -> false).get(180, TimeUnit.SECONDS);
                    assertEquals(expected, actual, "Uploaded bytes must match the local MD5");
                    if (round > 0) System.out.printf(Locale.ROOT, "SFTP_LIVE_UPLOAD round=%d depth=%d bytes=%d seconds=%.3f MiBps=%.2f md5Match=true%n",
                            round, depth, Files.size(local), seconds, sizeMiB / seconds);
                }
            } finally {
                if (ownsDirectory && ssh.isConnected()) try (var cleanup = ssh.newSFTPClient()) {
                    for (String path : created) if (cleanup.statExistence(path) != null) cleanup.rm(path);
                    cleanup.rmdir(directory);
                }
            }
        } finally { worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS); }
    }
}
