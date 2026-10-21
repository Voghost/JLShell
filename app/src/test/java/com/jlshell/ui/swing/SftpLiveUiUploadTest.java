package com.jlshell.ui.swing;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.service.FileChecksums;
import com.jlshell.sftp.support.SshjSftpService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import com.jlshell.ui.theme.AppTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.*;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in: real file-tool actions, real SSH server, generated data only. */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SftpLiveUiUploadTest {
    @TempDir Path temporary;

    @Test
    @EnabledIfSystemProperty(named = "jlshell.sftp.live", matches = "true")
    void uploadFromSwingAndVerifyMd5FromItsContextMenu() throws Exception {
        String host = System.getProperty("jlshell.sftp.live.host", "");
        assertFalse(host.isBlank(), "Set an explicit live SSH host");
        String knownHosts = System.getProperty("jlshell.sftp.live.knownHosts", "");
        assertTrue(Files.isRegularFile(Path.of(knownHosts)), "Verified known_hosts is required");
        int sizeMiB = Math.max(1, Math.min(128, Integer.getInteger("jlshell.sftp.live.sizeMiB", 16)));
        Path source = temporary.resolve("ui-upload.bin");
        var random = new Random(1882); byte[] block = new byte[64 * 1024];
        try (var output = Files.newOutputStream(source)) {
            for (int index = 0; index < sizeMiB * 16; index++) { random.nextBytes(block); output.write(block); }
        }
        String expected = FileChecksums.localMd5(source, () -> false);
        var worker = Executors.newFixedThreadPool(2);
        String directory = "/tmp/jlshell-sftp-ui-probe-" + UUID.randomUUID();
        boolean created = false;
        try (var ssh = new SSHClient()) {
            ssh.loadKnownHosts(Path.of(knownHosts).toFile()); ssh.setConnectTimeout(10_000); ssh.setTimeout(90_000);
            ssh.connect(host, Integer.getInteger("jlshell.sftp.live.port", 22));
            String user = System.getProperty("jlshell.sftp.live.user", "root");
            String key = System.getProperty("jlshell.sftp.live.key", "");
            if (key.isBlank()) ssh.authPublickey(user); else ssh.authPublickey(user, key);
            ssh.getConnection().setWindowSize(2 * 1024 * 1024);
            var session = mock(SshSession.class);
            when(session.unwrap(SSHClient.class)).thenReturn(Optional.of(ssh));
            var service = new SshjSftpService(worker);
            try (var files = ssh.newSFTPClient()) { files.mkdir(directory); created = true; }
            try {
                var browser = edt(() -> {
                    SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
                    return new SwingSftpBrowser(session, service, new I18nService(Locale.SIMPLIFIED_CHINESE), directory, temporary);
                });
                var frame = edt(() -> { var result = new JFrame("SFTP live test"); result.setContentPane(browser); result.setSize(680, 760); result.setVisible(true); return result; });
                try {
                    var local = edt(() -> (JTable) named(browser, "sftp.local.table"));
                    var remote = edt(() -> (JTable) named(browser, "sftp.remote.table"));
                    var upload = edt(() -> (JButton) named(browser, "sftp.upload"));
                    await(() -> local.getRowCount() == 1 && ((JTextField) named(browser, "sftp.remote.path")).getText().endsWith(directory.substring(directory.lastIndexOf('/'))), 20);
                    long start = System.nanoTime();
                    edt(() -> { local.setRowSelectionInterval(0, 0); assertTrue(upload.isEnabled()); upload.doClick(); return null; });
                    await(() -> remote.getRowCount() == 1 && "ui-upload.bin".equals(remote.getValueAt(0, 0)));
                    double seconds = (System.nanoTime() - start) / 1e9;
                    edt(() -> {
                        remote.setRowSelectionInterval(0, 0);
                        var cell = remote.getCellRect(0, 0, true);
                        remote.dispatchEvent(new MouseEvent(remote, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                                cell.x + 30, cell.y + 10, 1, true, MouseEvent.BUTTON3));
                        for (Window window : Window.getWindows()) if (window.isShowing()) {
                            if (!(named(window, "popup.list") instanceof JList<?> list)) continue;
                            for (int index = 0; index < list.getModel().getSize(); index++) {
                                if (list.getModel().getElementAt(index) instanceof IdeaListPopup.Row row && row.title().contains("MD5")) {
                                    assertTrue(row.enabled()); window.setVisible(false); row.action().run(); return null;
                                }
                            }
                        }
                        throw new AssertionError("MD5 menu missing");
                    });
                    await(() -> {
                        for (Window window : frame.getOwnedWindows()) if (window.isShowing()
                                && named(window, "sftp.md5.results") instanceof JTable table)
                            return expected.equals(table.getValueAt(0, 1));
                        return false;
                    });
                    System.out.printf(Locale.ROOT, "SFTP_LIVE_UI bytes=%d clickToRefreshedListSeconds=%.3f MiBps=%.2f md5MenuMatch=true%n",
                            Files.size(source), seconds, sizeMiB / seconds);
                } finally { edt(() -> { browser.close(); frame.dispose(); return null; }); }
            } finally {
                if (created && ssh.isConnected()) try (var cleanup = ssh.newSFTPClient()) {
                    if (cleanup.statExistence(directory + "/ui-upload.bin") != null) cleanup.rm(directory + "/ui-upload.bin");
                    cleanup.rmdir(directory);
                }
            }
        } finally { worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        var result = new java.util.concurrent.CompletableFuture<T>();
        SwingUtilities.invokeLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) throws Exception { await(condition, 180); }
    private static void await(BooleanSupplier condition, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Live file-tool action did not finish");
    }
    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
}
