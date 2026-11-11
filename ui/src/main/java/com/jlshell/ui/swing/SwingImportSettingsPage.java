package com.jlshell.ui.swing;

import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.ConnectionShareService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.importer.ManualJsonParser;
import com.jlshell.ui.service.importer.MobaXtermIniParser;
import com.jlshell.ui.service.importer.XshellXshParser;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Imports reuse the existing parsers and encrypted connection persistence, without UI toolkit models. */
public final class SwingImportSettingsPage extends JPanel implements AutoCloseable {
    private static final String SAMPLE = """
            [
              {"name":"web-server","host":"192.168.1.10","port":22,"user":"root","authType":"PASSWORD","password":"secret-password-here"},
              {"name":"db-server","host":"192.168.1.20","port":22,"user":"admin","authType":"PRIVATE_KEY","privateKeyPath":"/path/to/id_ed25519","passphrase":"optional-key-passphrase"}
            ]
            """;
    private static final String[] KEYS = {"name", "host", "port", "user", "authType", "password", "passphrase", "privateKeyPath", "description"};
    private final ConnectionProfileService service;
    private final String projectId;
    private final I18nService i18n;
    private final JLabel status = new JLabel(" ");
    private final DefaultTableModel model;
    private final JTable table;
    private final List<JButton> importActions = new ArrayList<>();
    private boolean importing;
    private boolean closed;

    public SwingImportSettingsPage(ConnectionProfileService service, String projectId, I18nService i18n) {
        super(new BorderLayout(0, 12));
        this.service = service;
        this.projectId = projectId;
        this.i18n = i18n;
        setBorder(BorderFactory.createEmptyBorder(16, 24, 20, 24));
        String[] columns = Arrays.stream(KEYS).map(key -> i18n.get("import.column." + key)).toArray(String[]::new);
        columns[7] = i18n.get("connection.privateKeyPath");
        columns[8] = i18n.get("connection.description");
        List<String> names = new ArrayList<>();
        names.add("#"); names.addAll(Arrays.asList(columns)); names.add("");
        model = new DefaultTableModel(names.toArray(String[]::new), 0) {
            @Override public boolean isCellEditable(int row, int column) { return column > 0 && column <= KEYS.length; }
            @Override public Object getValueAt(int row, int column) {
                if (column == 0) return row + 1;
                if (column == KEYS.length + 1) return "×";
                return super.getValueAt(row, column);
            }
        };
        table = new JTable(model);
        table.setName("settings.importTable");
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(30);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int index = 0; index < model.getColumnCount(); index++) table.getColumnModel().getColumn(index).setPreferredWidth(index == 0 || index == KEYS.length + 1 ? 36 : index == 3 ? 60 : 140);
        table.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(new String[]{"PASSWORD", "PRIVATE_KEY"})));
        for (int column : new int[]{6, 7}) {
            table.getColumnModel().getColumn(column).setCellEditor(new DefaultCellEditor(new JPasswordField()));
            table.getColumnModel().getColumn(column).setCellRenderer(new DefaultTableCellRenderer() {
                @Override protected void setValue(Object value) { setText(value == null || value.toString().isEmpty() ? "" : "•••"); }
            });
        }
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && table.columnAtPoint(event.getPoint()) == KEYS.length + 1) {
                    if (table.isEditing()) table.getCellEditor().stopCellEditing();
                    model.removeRow(table.convertRowIndexToModel(row));
                }
            }
        });
        if (service == null) {
            add(new JLabel(i18n.get("import.noFile")));
            return;
        }
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(sourceSection("mobaxterm", "ini", path -> parse(() -> new MobaXtermIniParser(projectId).parse(path)), false));
        content.add(Box.createVerticalStrut(24));
        content.add(sourceSection("xshell", "xsh", path -> parse(() -> new XshellXshParser(projectId).parseFile(path)), true));
        content.add(Box.createVerticalStrut(24));
        content.add(manualSection());
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll);
        add(status, BorderLayout.SOUTH);
        IdeaFormStyle.applyTree(content);
    }

    private JPanel sourceSection(String source, String extension, Function<Path, List<ConnectionFormData>> parser, boolean directory) {
        List<ConnectionFormData> pending = new ArrayList<>();
        JLabel found = new JLabel(" ");
        JButton load = new JButton(i18n.get("import." + source + ".browse"));
        JButton apply = new JButton(i18n.get("import.button.import"));
        apply.setEnabled(false);
        importActions.add(apply);
        load.addActionListener(event -> choose(load, false, extension, parser, values -> {
            pending.clear(); pending.addAll(values);
            found.setText(i18n.get("import.found", values.size()));
            apply.setEnabled(!values.isEmpty() && !importing);
        }));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(load);
        if (directory) {
            JButton folder = new JButton(i18n.get("import.xshell.browseDir"));
            folder.addActionListener(event -> choose(folder, true, null,
                    path -> parse(() -> new XshellXshParser(projectId).parseDirectory(path)), values -> {
                        pending.clear(); pending.addAll(values);
                        found.setText(i18n.get("import.found", values.size()));
                        apply.setEnabled(!values.isEmpty() && !importing);
                    }));
            actions.add(folder);
        }
        actions.add(apply);
        apply.addActionListener(event -> importProfiles(List.copyOf(pending), source.equals("xshell") ? "Xshell" : "MobaXterm"));
        JPanel section = section("import.section." + source, "import." + source + ".hint");
        section.add(actions);
        section.add(found);
        return section;
    }

    private JPanel manualSection() {
        JPanel section = section("import.section.manual", "import.manual.hint");
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 220));
        section.add(scroll);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton load = new JButton(i18n.get("import.manual.browseJson"));
        load.addActionListener(event -> choose(load, false, "json", path -> parse(() -> new ManualJsonParser(projectId).parse(path)), this::setRows));
        actions.add(load);
        JButton add = new JButton(i18n.get("import.manual.addRow"));
        add.addActionListener(event -> model.addRow(new Object[]{null, "", "", "22", "", "PASSWORD", "", "", "", "", null}));
        actions.add(add);
        JButton remove = new JButton(i18n.get("action.delete"));
        remove.addActionListener(event -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int[] selected = table.getSelectedRows();
            for (int index = selected.length - 1; index >= 0; index--) model.removeRow(table.convertRowIndexToModel(selected[index]));
        });
        actions.add(remove);
        JButton sample = new JButton(i18n.get("import.manual.sample"));
        sample.addActionListener(event -> showSample());
        actions.add(sample);
        JButton paste = new JButton(i18n.get("import.manual.pasteJson"));
        paste.addActionListener(event -> showPaste());
        actions.add(paste);
        JButton apply = new JButton(i18n.get("import.button.import"));
        importActions.add(apply);
        apply.addActionListener(event -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            List<Map<String, String>> values = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                Map<String, String> value = new LinkedHashMap<>();
                for (int column = 0; column < KEYS.length; column++) value.put(KEYS[column], Objects.toString(model.getValueAt(row, column + 1), ""));
                values.add(value);
            }
            importProfiles(new ManualJsonParser(projectId).fromRows(values), i18n.get("import.section.manual"));
        });
        actions.add(apply);
        section.add(actions);
        return section;
    }

    private void choose(JButton button, boolean directory, String extension, Function<Path, List<ConnectionFormData>> parser,
                        java.util.function.Consumer<List<ConnectionFormData>> loaded) {
        SwingSettingsFileChooser.openAsync(button.getText(), directory, extension).thenAccept(file -> {
            if (file == null || closed) return;
            button.setEnabled(false);
            CompletableFuture.supplyAsync(() -> parser.apply(file.toPath())).whenComplete((values, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return;
                button.setEnabled(true);
                if (error != null) status.setText(message(error)); else loaded.accept(values);
            }));
        });
    }

    private void setRows(List<ConnectionFormData> forms) {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        clearRows();
        for (ConnectionFormData form : forms) model.addRow(new Object[]{null, form.displayName(), form.host(), String.valueOf(form.port()),
                form.username(), form.authenticationType().name(), form.password(), form.passphrase(), form.privateKeyPath(), form.description(), null});
        status.setText(i18n.get("import.found", forms.size()));
    }

    private void showSample() {
        SwingFormDialog.showAsync(i18n.get("preferences.tab.import"), "jlshell.import.sample", i18n, () -> {
            JTextArea text = new JTextArea(SAMPLE);
            text.setEditable(false);
            return new SwingFormDialog.Form<>(new JScrollPane(text), () -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(SAMPLE), null);
                return Boolean.TRUE;
            }, i18n.get("import.manual.sample.copy"));
        }, 620, 360);
    }

    private void showPaste() {
        SwingFormDialog.showAsync(i18n.get("import.manual.pasteJson"), "jlshell.import.popup", i18n, () -> {
            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            JTextArea text = new JTextArea();
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setName("import.paste");
            try { text.setText(Objects.toString(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor), "")); }
            catch (Exception ignored) { /* Clipboard contents may not be text. */ }
            JLabel error = new JLabel(" ");
            body.add(new JScrollPane(text));
            body.add(error, BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(body, () -> {
                String value = text.getText().strip();
                if (value.isBlank()) return null;
                try {
                    ConnectionShareService shares = new ConnectionShareService();
                    if (shares.isShareText(value)) {
                        if (shares.requiresShareCode(value)) {
                            requestShareCode(value);
                        } else importProfiles(List.of(shares.importShareText(value, projectId)), i18n.get("import.section.share"));
                    } else setRows(new ManualJsonParser(projectId).parseJsonText(value));
                    text.setText("");
                    return Boolean.TRUE;
                } catch (RuntimeException failure) { error.setText(message(failure)); return null; }
            }, i18n.get("import.button.import"));
        }, 620, 400);
    }

    private void requestShareCode(String share) {
        // Create the child after the paste dialog closes so ownership returns to settings.
        SwingUtilities.invokeLater(() -> SwingFormDialog.showAsync(i18n.get("connection.share.importCodeTitle"), "jlshell.import.code", i18n, () -> {
            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            JPasswordField code = new JPasswordField();
            JLabel error = new JLabel(i18n.get("connection.share.importCodeHeader"));
            body.add(code);
            body.add(error, BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(body, () -> {
                char[] secret = code.getPassword();
                if (secret.length == 0) return null;
                try {
                    ConnectionFormData form = new ConnectionShareService().importShareText(share, secret, projectId);
                    importProfiles(List.of(form), i18n.get("import.section.share"));
                    code.setText("");
                    return Boolean.TRUE;
                } catch (RuntimeException failure) { error.setText(message(failure)); return null; }
                finally { Arrays.fill(secret, '\0'); }
            }, i18n.get("import.button.import"));
        }, 480, 220));
    }

    private void importProfiles(List<ConnectionFormData> profiles, String source) {
        if (closed || importing || profiles.isEmpty()) return;
        importing = true;
        List<Boolean> enabled = importActions.stream().map(JButton::isEnabled).toList();
        importActions.forEach(button -> button.setEnabled(false));
        String rootName = i18n.get("import.folder.root");
        CompletableFuture.supplyAsync(() -> {
            FolderProfile root = findFolder(rootName, null);
            FolderProfile origin = findFolder(source, root.id());
            FolderProfile batch = service.saveFolder(null, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), origin.id(), projectId);
            int success = 0;
            for (ConnectionFormData form : profiles) {
                try { service.saveImported(form.withFolderId(batch.id())); success++; }
                catch (RuntimeException error) {
                    org.slf4j.LoggerFactory.getLogger(SwingImportSettingsPage.class).warn("Connection import entry failed, type={}", error.getClass().getSimpleName());
                }
            }
            return success;
        }).whenComplete((success, error) -> SwingUtilities.invokeLater(() -> {
            importing = false;
            if (closed) return;
            for (int index = 0; index < importActions.size(); index++) importActions.get(index).setEnabled(enabled.get(index));
            status.setText(error == null ? i18n.get("import.result.count", success, profiles.size()) : message(error));
        }));
    }

    private FolderProfile findFolder(String name, String parent) {
        return service.listFolders(projectId).stream().filter(folder -> name.equals(folder.name()) && Objects.equals(parent, folder.parentId()))
                .findFirst().orElseGet(() -> service.saveFolder(null, name, parent, projectId));
    }

    private JPanel section(String titleKey, String descriptionKey) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel(i18n.get(titleKey));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1));
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        JTextArea hint = new JTextArea(i18n.get(descriptionKey));
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        panel.add(hint);
        panel.add(Box.createVerticalStrut(10));
        return panel;
    }

    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
    @FunctionalInterface private interface Parser { List<ConnectionFormData> parse() throws Exception; }
    private static List<ConnectionFormData> parse(Parser parser) {
        try { return parser.parse(); }
        catch (Exception error) { throw new IllegalArgumentException(message(error), error); }
    }
    private void clearRows() {
        for (int row = 0; row < model.getRowCount(); row++) { model.setValueAt("", row, 6); model.setValueAt("", row, 7); }
        model.setRowCount(0);
    }
    @Override public void close() {
        closed = true;
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        clearRows();
    }
}
