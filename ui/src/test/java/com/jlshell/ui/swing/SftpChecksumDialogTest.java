package com.jlshell.ui.swing;

import com.jlshell.sftp.service.FileChecksums;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SftpChecksumDialogTest {
    @TempDir Path temporary;
    private static final I18nService I18N = new I18nService(Locale.SIMPLIFIED_CHINESE);

    @BeforeAll static void theme() throws Exception {
        edt(() -> { SwingTheme.install(AppTheme.LIGHT, "#3574f0", null, 13); return null; });
    }

    @Test void hashesRealLocalFilesCopiesResultsAndKeepsTheOwnerVisible() throws Exception {
        Path abc = Files.writeString(temporary.resolve("中文.txt"), "abc");
        Path empty = Files.writeString(temporary.resolve("empty"), "");
        JFrame owner = edt(() -> { var frame = new JFrame("Files"); frame.setSize(500, 500); frame.setVisible(true); return frame; });
        SftpChecksumDialog dialog = edt(() -> new SftpChecksumDialog(owner.getRootPane(), List.of(
                new SftpChecksumDialog.File("中文.txt", abc.toString()), new SftpChecksumDialog.File("empty", empty.toString())),
                I18N, (file, cancelled) -> CompletableFuture.supplyAsync(() -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    try { return FileChecksums.localMd5(Path.of(file.path()), cancelled); }
                    catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
                })));
        try {
            await(() -> dialog.copyText().split("\\R").length == 2);
            edt(() -> {
                assertTrue(dialog.copyText().contains("900150983cd24fb0d6963f7d28e17f72  中文.txt"));
                assertTrue(dialog.copyText().contains("d41d8cd98f00b204e9800998ecf8427e  empty"));
                assertEquals(owner, dialog.getOwner());
                assertFalse(dialog.isModal());
                dialog.dispose();
                assertTrue(owner.isShowing());
                assertEquals(0, owner.getExtendedState() & JFrame.ICONIFIED);
                return null;
            });
        } finally { edt(() -> { dialog.dispose(); owner.dispose(); return null; }); }
    }

    @Test void cancelStopsPendingFilesAndIgnoresLateCompletion() throws Exception {
        JFrame owner = edt(() -> { var frame = new JFrame(); frame.setSize(300, 300); frame.setVisible(true); return frame; });
        var pending = new CompletableFuture<String>();
        var stopped = new AtomicReference<BooleanSupplier>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SftpChecksumDialog dialog = edt(() -> new SftpChecksumDialog(owner.getRootPane(), List.of(
                new SftpChecksumDialog.File("first", "/first"), new SftpChecksumDialog.File("second", "/second")),
                I18N, (file, cancelled) -> { calls.incrementAndGet(); stopped.set(cancelled); return pending; }));
        try {
            edt(() -> { named(dialog, "sftp.md5.cancel", JButton.class).doClick(); return null; });
            assertTrue(stopped.get().getAsBoolean());
            pending.complete("900150983cd24fb0d6963f7d28e17f72");
            edt(() -> {
                assertEquals("", dialog.copyText()); assertEquals(1, calls.get());
                assertFalse(named(dialog, "sftp.md5.copy", JButton.class).isEnabled());
                assertEquals(I18N.get("sftp.cancelled"), named(dialog, "sftp.md5.results", JTable.class).getValueAt(0, 2));
                return null;
            });
        } finally { edt(() -> { dialog.dispose(); owner.dispose(); return null; }); }
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        return SwingExecutors.supplyOnEdtAsync(action).get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (edt(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Checksum UI did not finish");
    }

    private static <T> T named(Component component, String name, Class<T> type) {
        if (name.equals(component.getName()) && type.isInstance(component)) return type.cast(component);
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            T result = named(child, name, type); if (result != null) return result;
        }
        return null;
    }
}
