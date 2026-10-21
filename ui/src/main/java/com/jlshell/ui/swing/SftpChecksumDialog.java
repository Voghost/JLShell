package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Owned, non-modal results; computations remain cancellable when this dialog or its browser closes. */
final class SftpChecksumDialog extends JDialog {
    record File(String name, String path) {}
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<File> files;
    private final List<String> hashes;
    private final List<String> states;
    private final I18nService i18n;
    private final JButton copy;
    private final JButton stop;
    private final JLabel summary = new JLabel();
    private final Results model = new Results();
    private boolean running = true;

    SftpChecksumDialog(Component parent, List<File> files, I18nService i18n,
                       BiFunction<File, BooleanSupplier, CompletableFuture<String>> compute) {
        super(SwingUtilities.getWindowAncestor(parent), i18n.get("sftp.md5.title"), Dialog.ModalityType.MODELESS);
        this.files = List.copyOf(files); this.i18n = i18n;
        hashes = new ArrayList<>(java.util.Collections.nCopies(files.size(), ""));
        states = new ArrayList<>(java.util.Collections.nCopies(files.size(), i18n.get("sftp.md5.waiting")));
        setName("sftp.md5.dialog"); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        summary.setName("sftp.md5.summary"); summary.putClientProperty("html.disable", true);
        summary.setText(i18n.get("sftp.md5.running")); body.add(summary, BorderLayout.NORTH);
        JTable table = new JTable(model); table.setName("sftp.md5.results");
        table.setRowHeight(28); table.setFillsViewportHeight(true);
        var text = new DefaultTableCellRenderer(); text.putClientProperty("html.disable", true);
        table.setDefaultRenderer(Object.class, text);
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        body.add(new JScrollPane(table));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        copy = new JButton(i18n.get("sftp.md5.copy")); copy.setName("sftp.md5.copy"); copy.setEnabled(false);
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(copyText()), null));
        stop = new JButton(i18n.get("sftp.cancel")); stop.setName("sftp.md5.cancel");
        stop.addActionListener(event -> { if (running) cancel(); else dispose(); });
        buttons.add(copy); buttons.add(stop); body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body); setMinimumSize(new Dimension(560, 250)); setSize(680, 330); setLocationRelativeTo(parent);
        addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { cancelled.set(true); } });
        setVisible(true);
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
        for (int index = 0; index < files.size(); index++) {
            int row = index;
            sequence = sequence.thenCompose(unused -> {
                if (cancelled.get()) return CompletableFuture.completedFuture(null);
                SwingUtilities.invokeLater(() -> update(row, "", i18n.get("sftp.md5.running")));
                CompletableFuture<String> pending;
                try { pending = java.util.Objects.requireNonNull(compute.apply(this.files.get(row), cancelled::get)); }
                catch (RuntimeException error) { pending = CompletableFuture.failedFuture(error); }
                return pending.handle((hash, error) -> {
                    SwingUtilities.invokeLater(() -> {
                        if (!isDisplayable() || cancelled.get()) return;
                        boolean valid = error == null && hash != null && hash.matches("[0-9a-fA-F]{32}");
                        update(row, valid ? hash.toLowerCase(java.util.Locale.ROOT) : "",
                                i18n.get(valid ? "sftp.md5.done" : "sftp.md5.failed"));
                        if (error != null) org.slf4j.LoggerFactory.getLogger(SftpChecksumDialog.class)
                                .warn("File MD5 calculation failed", error);
                    });
                    return (Void) null;
                });
            });
        }
        sequence.whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable() || cancelled.get()) return;
            running = false;
            summary.setText(i18n.get("sftp.md5.finished"));
            stop.setText(i18n.get("sftp.md5.close"));
        }));
    }

    @Override public void dispose() {
        cancelled.set(true);
        super.dispose();
    }

    void cancel() {
        cancelled.set(true); running = false;
        for (int row = 0; row < files.size(); row++) if (hashes.get(row).isEmpty())
            states.set(row, i18n.get("sftp.cancelled"));
        model.fireTableDataChanged(); summary.setText(i18n.get("sftp.cancelled"));
        stop.setText(i18n.get("sftp.md5.close"));
    }

    private void update(int row, String hash, String state) {
        if (!isDisplayable() || cancelled.get()) return;
        hashes.set(row, hash); states.set(row, state); model.fireTableRowsUpdated(row, row);
        copy.setEnabled(hashes.stream().anyMatch(value -> !value.isEmpty()));
    }

    String copyText() {
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < files.size(); row++) if (!hashes.get(row).isEmpty()) {
            String name = files.get(row).name().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
            lines.add(hashes.get(row) + "  " + name);
        }
        return String.join(System.lineSeparator(), lines);
    }

    private final class Results extends AbstractTableModel {
        public int getRowCount() { return files.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int column) { return column == 0 ? i18n.get("sftp.name")
                : column == 1 ? "MD5" : i18n.get("sftp.md5.status"); }
        public Object getValueAt(int row, int column) { return column == 0 ? files.get(row).name()
                : column == 1 ? hashes.get(row) : states.get(row); }
    }
}
