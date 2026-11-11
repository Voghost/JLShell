package com.jlshell.ui.view;

import com.jlshell.core.model.*;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.dialog.VaultManagerDialog;
import com.jlshell.ui.model.*;
import com.jlshell.ui.service.ConnectionShareService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.support.ExceptionMessages;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Native connection identity, authentication, options and connection-test form. */
public final class ConnectionDialog {
    private ConnectionDialog() {}
    @FunctionalInterface public interface ConnectionTester { CompletableFuture<String> test(ConnectionTestContext context); }
    public record ConnectionTestContext(ConnectionRequest request, String connectionId, String projectId, String displayName) {}

    public static Optional<ConnectionFormData> show(Object owner, I18nService i18n, ThemeService themes, ConnectionFormData initial,
            List<ProjectProfile> projects, List<FolderProfile> folders, VaultService vault, ConnectionTester tester, int timeout) {
        return Optional.ofNullable(SwingFormDialog.await(showAsync(owner, i18n, themes, initial, projects, folders, vault, tester, timeout)));
    }

    public static CompletableFuture<ConnectionFormData> showAsync(Object owner, I18nService i18n, ThemeService themes, ConnectionFormData initial,
            List<ProjectProfile> projects, List<FolderProfile> folders, VaultService vault, ConnectionTester tester, int timeout) {
        return SwingFormDialog.showAsync(i18n.get(initial.id() == null ? "dialog.connection.create" : "dialog.connection.edit"),
                "jlshell.connection", i18n, themes, () -> {
                    Editor editor = new Editor(initial, projects, folders, vault, tester, timeout, i18n, themes);
                    return new SwingFormDialog.Form<>(editor, editor::submit);
                }, 780, 570);
    }

    private static final class Editor extends JPanel implements AutoCloseable {
        private final ConnectionFormData initial;
        private final I18nService i18n;
        private final ThemeService themes;
        private final VaultService vault;
        private final ConnectionTester tester;
        private final List<ProjectProfile> projects;
        private final int timeout;
        private final JComboBox<ConnectionType> type = new JComboBox<>(ConnectionType.values());
        private final JTextField name = new JTextField(), host = new JTextField(), port = new JTextField(), user = new JTextField(), keyPath = new JTextField(), remote = new JTextField();
        private final JPasswordField password = new JPasswordField(), passphrase = new JPasswordField();
        private final JComboBox<AuthenticationType> auth = new JComboBox<>(AuthenticationType.values());
        private final JComboBox<HostKeyVerificationMode> hostKey = new JComboBox<>(HostKeyVerificationMode.values());
        private final JComboBox<ProjectProfile> project;
        private final JComboBox<FolderProfile> folder;
        private final JComboBox<VaultEntryProfile> credentials = new JComboBox<>();
        private final VaultEntryProfile none;
        private final JTextArea description = new JTextArea(4, 30);
        private final JCheckBox favorite;
        private final JLabel result = new JLabel(" ");
        private final JTabbedPane tabs = new JTabbedPane();
        private final JButton test;
        private final List<JPanel> sshRows = new ArrayList<>();
        private final JPanel passwordRow, keyRow, passphraseRow, localHelp;
        private String vaultId, keyContent;
        private long vaultRevision, listRevision;
        private boolean closed, updating, loading, testing;

        Editor(ConnectionFormData initial, List<ProjectProfile> projects, List<FolderProfile> folders,
               VaultService vault, ConnectionTester tester, int timeout, I18nService i18n, ThemeService themes) {
            super(new BorderLayout(0, 10));
            this.initial = initial; this.projects = projects; this.vault = vault; this.tester = tester;
            this.timeout = timeout; this.i18n = i18n; this.themes = themes;
            setName("dialog.content");
            setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
            name.setName("connection-name"); host.setName("connection-host"); port.setName("connection-port"); user.setName("connection-user");
            type.setName("connection-type"); auth.setName("connection-auth"); credentials.setName("connection-vault");
            password.setName("connection-password"); keyPath.setName("connection-key-path"); passphrase.setName("connection-key-passphrase");
            labels(type, value -> value == ConnectionType.SSH ? "SSH" : i18n.get("connection.type.localShell"));
            labels(auth, value -> i18n.get("connection.auth." + value.name()));
            labels(hostKey, value -> i18n.get("connection.hostKey." + value.name()));
            var projectOptions = new ArrayList<ProjectProfile>();
            projectOptions.add(new ProjectProfile(null, i18n.get("project.label.default"), null)); projectOptions.addAll(projects);
            project = new JComboBox<>(projectOptions.toArray(ProjectProfile[]::new)); labels(project, ProjectProfile::name);
            selectProject(initial.projectId());
            var folderOptions = new ArrayList<FolderProfile>();
            folderOptions.add(new FolderProfile(null, i18n.get("folder.none"), null, null, 0)); folderOptions.addAll(folders);
            folder = new JComboBox<>(folderOptions.toArray(FolderProfile[]::new)); labels(folder, FolderProfile::name);
            folderOptions.stream().filter(value -> Objects.equals(value.id(), initial.folderId())).findFirst().ifPresent(folder::setSelectedItem);
            none = new VaultEntryProfile(null, i18n.get("vault.select.none"), AuthenticationType.PASSWORD, VaultEncryptionMode.SYSTEM, null, null);
            credentials.addItem(none);
            labels(credentials, value -> value.name() + (value.id() == null ? "" : " · " + value.authenticationType().name()));
            add(field("connection.displayName", name), BorderLayout.NORTH);
            JPanel general = vertical(), options = vertical(), ssh = vertical();
            localHelp = field("", new JLabel(i18n.get("connection.dialog.local.help")));
            general.add(localHelp); general.add(field("connection.type", type));
            JPanel address = new JPanel(new BorderLayout(10, 0)); address.add(host);
            JPanel portArea = new JPanel(new BorderLayout(6, 0)); portArea.add(new JLabel(i18n.get("connection.port")), BorderLayout.WEST);
            port.setColumns(5); portArea.add(port); address.add(portArea, BorderLayout.EAST);
            sshRows.add(field("connection.host", address));
            sshRows.add(field("connection.authType", auth)); sshRows.add(field("connection.username", user));
            JButton manage = new JButton(i18n.get("connection.vaultManage")); manage.setName("connection-manage-vault");
            JPanel vaultRow = new JPanel(new BorderLayout(8, 0)); vaultRow.add(credentials); vaultRow.add(manage, BorderLayout.EAST);
            sshRows.add(field("connection.vaultEntry", vaultRow));
            passwordRow = field("connection.password", password); keyRow = field("connection.privateKey", keyPath); passphraseRow = field("connection.passphrase", passphrase);
            sshRows.add(passwordRow); sshRows.add(keyRow); sshRows.add(passphraseRow);
            sshRows.forEach(general::add);
            options.add(field("project.field.name", project)); options.add(field("folder.field.name", folder));
            JPanel remoteRow = field("connection.remotePath", remote); sshRows.add(remoteRow); options.add(remoteRow);
            description.setLineWrap(true); description.setWrapStyleWord(true);
            options.add(field("connection.description", new JScrollPane(description)));
            favorite = new JCheckBox(i18n.get("dialog.connection.favorite")); options.add(field("", favorite));
            ssh.add(new JLabel(i18n.get("connection.dialog.ssh.help"))); ssh.add(field("connection.hostKeyMode", hostKey));
            tabs.addTab(i18n.get("connection.dialog.tab.general"), new JScrollPane(general));
            tabs.addTab(i18n.get("connection.dialog.tab.options"), new JScrollPane(options));
            tabs.addTab(i18n.get("connection.dialog.tab.sshSsl"), new JScrollPane(ssh));
            add(tabs);
            test = new JButton(i18n.get("action.testConnection")); test.setName("connection-test");
            JButton paste = new JButton(i18n.get("connection.share.pasteButton")); paste.setVisible(initial.id() == null);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); actions.add(test); actions.add(paste);
            JPanel footer = new JPanel(new BorderLayout(0, 6)); footer.add(actions); footer.add(result, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
            fill(initial);
            vaultId = initial.vaultEntryId(); keyContent = initial.keyContent();
            type.addActionListener(event -> updateAuth()); auth.addActionListener(event -> updateAuth());
            credentials.addActionListener(event -> { if (!updating) loadVault(); });
            project.addActionListener(event -> reloadVault(null));
            manage.addActionListener(event -> VaultManagerDialog.show(null, vault, i18n, themes, projectId(), projects, null)
                    .whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> { if (!closed) reloadVault(vaultId); })));
            paste.addActionListener(event -> showPasteShareAsync(i18n).thenAccept(value -> { if (!closed && value != null) { fill(value); selectManual(); } }));
            test.addActionListener(event -> test());
            for (JTextField field : List.of(name, host, port, user)) field.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { updateValidation(); }
                public void removeUpdate(DocumentEvent event) { updateValidation(); }
                public void changedUpdate(DocumentEvent event) { updateValidation(); }
            });
            addHierarchyListener(event -> SwingUtilities.invokeLater(this::updateValidation));
            updateAuth(); reloadVault(initial.vaultEntryId());
        }
        private JPanel field(String key, JComponent control) {
            JPanel row = new JPanel(new BorderLayout(16, 0));
            JLabel label = new JLabel(key.isEmpty() ? "" : i18n.get(key)); label.setPreferredSize(new Dimension(122, 28));
            row.add(label, BorderLayout.WEST); row.add(control);
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            row.setAlignmentX(LEFT_ALIGNMENT); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            return row;
        }
        private static JPanel vertical() {
            JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 16, 12)); return panel;
        }
        private String projectId() { return project.getSelectedItem() instanceof ProjectProfile value ? value.id() : null; }
        private void selectProject(String id) {
            for (int index = 0; index < project.getItemCount(); index++) if (Objects.equals(project.getItemAt(index).id(), id)) project.setSelectedIndex(index);
        }
        private void fill(ConnectionFormData form) {
            type.setSelectedItem(form.connectionType()); name.setText(form.displayName()); host.setText(form.host()); port.setText(String.valueOf(form.port()));
            user.setText(form.username()); auth.setSelectedItem(form.authenticationType()); password.setText(form.password()); keyPath.setText(form.privateKeyPath());
            passphrase.setText(form.passphrase()); hostKey.setSelectedItem(form.hostKeyVerificationMode()); remote.setText(form.defaultRemotePath());
            description.setText(form.description()); favorite.setSelected(form.favorite());
        }
        private void updateAuth() {
            boolean ssh = type.getSelectedItem() == ConnectionType.SSH, key = auth.getSelectedItem() == AuthenticationType.PRIVATE_KEY;
            sshRows.forEach(row -> row.setVisible(ssh)); localHelp.setVisible(!ssh);
            passwordRow.setVisible(ssh && !key); keyRow.setVisible(ssh && key); passphraseRow.setVisible(ssh && key);
            auth.setEnabled(vaultId == null && !loading); password.setEnabled(vaultId == null && !key && !loading);
            keyPath.setEnabled(vaultId == null && key && !loading); passphrase.setEnabled(vaultId == null && key && !loading);
            tabs.setEnabledAt(2, ssh); test.setVisible(ssh); test.setEnabled(!testing && !loading && tester != null);
            updateValidation(); revalidate(); repaint();
        }
        private boolean valid() {
            if (type.getSelectedItem() == ConnectionType.LOCAL_SHELL) return !name.getText().isBlank();
            try {
                int number = Integer.parseInt(port.getText().isBlank() ? "22" : port.getText().strip());
                return !host.getText().isBlank() && !user.getText().isBlank() && number > 0 && number <= 65535;
            } catch (NumberFormatException invalid) { return false; }
        }
        private void updateValidation() {
            if (closed) return;
            JRootPane root = getRootPane();
            if (root != null && root.getDefaultButton() != null) root.getDefaultButton().setEnabled(valid() && !loading);
        }
        private void selectManual() {
            vaultRevision++; vaultId = null; keyContent = null; loading = false;
            updating = true; credentials.setSelectedItem(none); updating = false; updateAuth();
        }
        private void reloadVault(String selected) {
            if (vault == null) return;
            long revision = ++listRevision;
            String id = projectId();
            CompletableFuture.supplyAsync(() -> vault.listByProject(id)).whenComplete((entries, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || revision != listRevision) return;
                if (error != null) { result.setText(ExceptionMessages.userMessage(error)); return; }
                updating = true;
                credentials.removeAllItems(); credentials.addItem(none); entries.forEach(credentials::addItem);
                entries.stream().filter(entry -> Objects.equals(entry.id(), selected)).findFirst().ifPresent(credentials::setSelectedItem);
                updating = false;
                if (selected != null) loadVault(); else selectManual();
            }));
        }
        private void loadVault() {
            VaultEntryProfile entry = (VaultEntryProfile) credentials.getSelectedItem();
            if (entry == null || entry.id() == null) { selectManual(); return; }
            long revision = ++vaultRevision; loading = true; updateAuth();
            CompletableFuture<Boolean> unlocked;
            if (entry.encryptionMode() == VaultEncryptionMode.CUSTOM && !vault.getKeyService().isUnlocked()) {
                unlocked = new VaultManagerDialog.PasswordDialog(null, i18n, themes, i18n.get("vault.unlock.title"), false).showAsync()
                        .thenApplyAsync(secret -> {
                            if (secret == null) return false;
                            try { return vault.getKeyService().unlock(secret); }
                            finally { Arrays.fill(secret, '\0'); }
                        });
            } else unlocked = CompletableFuture.completedFuture(true);
            unlocked.thenApplyAsync(available -> available ? vault.loadForm(entry.id()) : null)
                    .whenComplete((form, error) -> SwingUtilities.invokeLater(() -> {
                        if (closed || revision != vaultRevision) return;
                        loading = false;
                        if (error != null || form == null) {
                            selectManual();
                            result.setText(error == null ? i18n.get("vault.unlock.failed") : ExceptionMessages.userMessage(error)); return;
                        }
                        vaultId = entry.id(); keyContent = form.keyContent(); auth.setSelectedItem(form.authenticationType());
                        password.setText(Objects.toString(form.password(), "")); keyPath.setText(Objects.toString(form.privateKeyPath(), ""));
                        passphrase.setText(Objects.toString(form.passphrase(), "")); updateAuth();
                    }));
        }
        private ConnectionFormData submit() {
            if (loading || !valid()) return null;
            ProjectProfile selectedProject = (ProjectProfile) project.getSelectedItem();
            FolderProfile selectedFolder = (FolderProfile) folder.getSelectedItem();
            return new ConnectionFormData(initial.id(), name.getText(), host.getText(), Integer.parseInt(port.getText().isBlank() ? "22" : port.getText().strip()),
                    user.getText(), (AuthenticationType) auth.getSelectedItem(), secret(password), keyPath.getText(), secret(passphrase),
                    (HostKeyVerificationMode) hostKey.getSelectedItem(), description.getText(), remote.getText(), favorite.isSelected(),
                    selectedProject == null ? null : selectedProject.id(), (ConnectionType) type.getSelectedItem(), selectedFolder == null ? null : selectedFolder.id(), vaultId, keyContent);
        }
        private void test() {
            ConnectionFormData form = submit();
            if (form == null || tester == null || testing) { result.setText(i18n.get("testConnection.fillFields")); return; }
            if (form.vaultEntryId() == null && (form.authenticationType() == AuthenticationType.PASSWORD ? form.password().isEmpty() : form.privateKeyPath().isBlank())) {
                result.setText(i18n.get("testConnection.fillFields")); return;
            }
            testing = true; updateAuth(); result.setText(i18n.get("testConnection.testing"));
            CompletableFuture.supplyAsync(() -> {
                ConnectionRequest request = form.vaultEntryId() == null ? buildTestRequest(form.displayName(), form.host().strip(), form.port(), form.username().strip(),
                        form.authenticationType(), form.password(), form.privateKeyPath().strip(), form.passphrase(), form.hostKeyVerificationMode(), timeout)
                        : new ConnectionRequest(form.displayName(), new ConnectionTarget(form.host().strip(), form.port(), form.username().strip(), Duration.ofSeconds(timeout), null),
                                form.authenticationType() == AuthenticationType.PASSWORD ? AuthenticationMethod.PASSWORD : AuthenticationMethod.PRIVATE_KEY,
                                vault.toCredentialPayload(form.vaultEntryId()), form.hostKeyVerificationMode());
                return new ConnectionTestContext(request, initial.id(), form.projectId(), form.displayName());
            }).thenCompose(context -> {
                try { return tester.test(context).whenComplete((value, error) -> context.request().credential().clear()); }
                catch (RuntimeException | Error error) { context.request().credential().clear(); throw error; }
            }).whenComplete((message, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return;
                testing = false; updateAuth();
                result.setText(error == null ? Objects.toString(message, i18n.get("testConnection.success"))
                        : i18n.get("testConnection.failed", ExceptionMessages.userMessage(error)));
            }));
        }
        @Override public void close() {
            closed = true; vaultRevision++; listRevision++; password.setText(""); passphrase.setText(""); keyContent = null;
        }
    }

    public static Optional<ConnectionFormData> showPasteShareDialog(Object owner, I18nService i18n, ThemeService themes) {
        return Optional.ofNullable(SwingFormDialog.await(showPasteShareAsync(i18n)));
    }
    public static CompletableFuture<ConnectionFormData> showPasteShareAsync(I18nService i18n) {
        return SwingFormDialog.showAsync(i18n.get("connection.share.pasteTitle"), "jlshell.connection.share", i18n, () -> {
            JTextArea share = new JTextArea(8, 48); share.setLineWrap(true); share.setWrapStyleWord(true);
            try {
                String text = Objects.toString(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor), "");
                if (new ConnectionShareService().isShareText(text)) share.setText(text);
            } catch (Exception ignored) { /* Clipboard contents may not be text. */ }
            JPasswordField code = new JPasswordField();
            code.putClientProperty("JTextField.placeholderText", i18n.get("connection.share.code"));
            JLabel error = new JLabel(" ");
            JPanel body = new JPanel(new BorderLayout(0, 8)); body.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            body.add(new JScrollPane(share));
            JPanel footer = new JPanel(new BorderLayout(0, 6)); footer.add(code); footer.add(error, BorderLayout.SOUTH); body.add(footer, BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(body, () -> {
                char[] secret = code.getPassword();
                try {
                    var shares = new ConnectionShareService();
                    String text = share.getText().strip();
                    if (!shares.isShareText(text)) { error.setText(i18n.get("connection.share.invalidText")); return null; }
                    if (shares.requiresShareCode(text) && secret.length == 0) { error.setText(i18n.get("connection.share.importCodeHeader")); return null; }
                    ConnectionFormData result = shares.requiresShareCode(text) ? shares.importShareText(text, secret, null) : shares.importShareText(text, null);
                    share.setText(""); code.setText(""); return result;
                } catch (RuntimeException failure) { error.setText(ExceptionMessages.userMessage(failure)); return null; }
                finally { Arrays.fill(secret, '\0'); }
            }, i18n.get("connection.share.fillButton"));
        }, 620, 380);
    }
    private static <T> void labels(JComboBox<T> combo, java.util.function.Function<T, String> text) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                @SuppressWarnings("unchecked") T item = (T) value;
                return super.getListCellRendererComponent(list, item == null ? "" : text.apply(item), index, selected, focus);
            }
        });
    }
    private static String secret(JPasswordField field) {
        char[] value = field.getPassword();
        try { return new String(value); } finally { Arrays.fill(value, '\0'); }
    }
    private static ConnectionRequest buildTestRequest(String displayName, String host, int port, String username, AuthenticationType authType,
            String password, String keyPath, String passphrase, HostKeyVerificationMode mode, int timeout) {
        char[] secret = (authType == AuthenticationType.PASSWORD ? password : passphrase).toCharArray();
        try {
            CredentialPayload credential = authType == AuthenticationType.PASSWORD ? CredentialPayload.forPassword(secret) : CredentialPayload.forPrivateKey(Path.of(keyPath), secret);
            return new ConnectionRequest(displayName, new ConnectionTarget(host, port, username, Duration.ofSeconds(timeout), null),
                    authType == AuthenticationType.PASSWORD ? AuthenticationMethod.PASSWORD : AuthenticationMethod.PRIVATE_KEY, credential, mode);
        } finally { Arrays.fill(secret, '\0'); }
    }
}
