package com.jlshell.ui.dialog;

import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.VaultEntryFormData;
import com.jlshell.ui.model.VaultEntryProfile;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.swing.SwingSettingsFileChooser;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Native credential manager. Secret values remain in the form only while it is open. */
public final class VaultManagerDialog {
    private VaultManagerDialog() {}

    public static CompletableFuture<Void> show(Object owner, VaultService service, I18nService i18n, ThemeService themes,
                            String activeProjectId, List<ProjectProfile> projects, Runnable changed) {
        return SwingFormDialog.showMessageAsync(i18n.get("vault.manager.title"), "jlshell.vault", i18n, themes,
                () -> new Editor(service, i18n, themes, activeProjectId, projects), 900, 610)
                .thenRun(() -> { if (changed != null) changed.run(); });
    }

    private static final class Editor extends JPanel implements AutoCloseable {
        private final VaultService service;
        private final I18nService i18n;
        private final ThemeService themes;
        private final DefaultListModel<VaultEntryProfile> entries = new DefaultListModel<>();
        private final JList<VaultEntryProfile> list = new JList<>(entries);
        private final JComboBox<ProjectProfile> scope, project;
        private final JTextField name = new JTextField(), keyPath = new JTextField();
        private final JPasswordField password = new JPasswordField(), passphrase = new JPasswordField();
        private final JTextArea keyContent = new JTextArea(5, 20);
        private final JComboBox<AuthenticationType> auth = new JComboBox<>(AuthenticationType.values());
        private final JComboBox<VaultEncryptionMode> encryption = new JComboBox<>(VaultEncryptionMode.values());
        private final JLabel status = new JLabel(" "), lockStatus = new JLabel(), hint = new JLabel(" ");
        private final JButton lock = new JButton(), save = new JButton(), delete = new JButton();
        private final JPanel fields = new JPanel(new GridBagLayout());
        private final JPanel passwordRow, keyRow, pathRow, passphraseRow;
        private long loadRevision, selectionRevision;
        private boolean closed, busy, loading;
        private int fieldRow;

        Editor(VaultService service, I18nService i18n, ThemeService themes, String activeProject, List<ProjectProfile> projects) {
            super(new BorderLayout(0, 12));
            this.service = service; this.i18n = i18n; this.themes = themes;
            setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
            var options = new java.util.ArrayList<ProjectProfile>();
            options.add(new ProjectProfile(null, i18n.get("project.label.default"), null)); options.addAll(projects);
            scope = new JComboBox<>(options.toArray(ProjectProfile[]::new));
            project = new JComboBox<>(options.toArray(ProjectProfile[]::new));
            for (JComboBox<ProjectProfile> combo : List.of(scope, project)) {
                combo.setRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                        return super.getListCellRendererComponent(list, value instanceof ProjectProfile p ? p.name() : "", index, selected, focus);
                    }
                });
            }
            options.stream().filter(p -> Objects.equals(p.id(), activeProject)).findFirst().ifPresent(scope::setSelectedItem);
            project.setSelectedItem(scope.getSelectedItem());
            JPanel lockBar = new JPanel(new BorderLayout());
            lockBar.add(lockStatus); lockBar.add(lock, BorderLayout.EAST);
            add(lockBar, BorderLayout.NORTH);
            lock.addActionListener(event -> changeLock());
            list.setName("vault-list"); scope.setName("vault-scope"); project.setName("vault-project");
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    VaultEntryProfile entry = (VaultEntryProfile) value;
                    return super.getListCellRendererComponent(list, entry == null ? "" : entry.name() + " · "
                            + (entry.authenticationType() == AuthenticationType.PASSWORD ? "PWD" : "KEY") + " · " + entry.encryptionMode(), index, selected, focus);
                }
            });
            JPanel left = new JPanel(new BorderLayout(0, 10));
            left.add(scope, BorderLayout.NORTH); left.add(new JScrollPane(list));
            JButton create = new JButton(i18n.get("vault.action.new")); create.setName("vault-new");
            delete.setText(i18n.get("vault.action.delete")); delete.setName("vault-delete");
            JPanel listActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); listActions.add(create); listActions.add(delete);
            left.add(listActions, BorderLayout.SOUTH); left.setPreferredSize(new Dimension(225, 0));
            name.setName("vault-name"); auth.setName("vault-auth"); encryption.setName("vault-encryption");
            password.setName("vault-password"); keyContent.setName("vault-key-content"); keyPath.setName("vault-key-path"); passphrase.setName("vault-passphrase");
            field("vault.field.name", name); field("vault.field.authType", auth);
            JPanel mode = new JPanel(new BorderLayout(8, 0)); mode.add(encryption); mode.add(hint, BorderLayout.SOUTH);
            field("vault.field.encMode", mode);
            passwordRow = field("vault.field.password", password);
            keyRow = field("vault.field.keyContent", new JScrollPane(keyContent));
            JButton importKey = new JButton(i18n.get("vault.action.importKey"));
            JPanel path = new JPanel(new BorderLayout(8, 0)); path.add(keyPath); path.add(importKey, BorderLayout.EAST);
            pathRow = field("vault.field.keyPath", path);
            passphraseRow = field("vault.field.passphrase", passphrase);
            field("vault.field.project", project);
            GridBagConstraints spacer = new GridBagConstraints();
            spacer.gridy = fieldRow; spacer.weighty = 1; spacer.fill = GridBagConstraints.VERTICAL;
            fields.add(Box.createVerticalGlue(), spacer);
            save.setText(i18n.get("vault.action.save")); save.setName("vault-save");
            JPanel right = new JPanel(new BorderLayout(0, 12));
            JScrollPane formScroll = new JScrollPane(fields); formScroll.setBorder(BorderFactory.createEmptyBorder());
            right.add(formScroll);
            JPanel formActions = new JPanel(new BorderLayout()); formActions.add(status); formActions.add(save, BorderLayout.EAST);
            right.add(formActions, BorderLayout.SOUTH);
            JPanel body = new JPanel(new BorderLayout(24, 0)); body.add(left, BorderLayout.WEST); body.add(right);
            add(body);
            auth.addActionListener(event -> updateAuth());
            encryption.addActionListener(event -> updateLock());
            scope.addActionListener(event -> reload(null));
            list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) loadSelected(); });
            create.addActionListener(event -> { if (!busy) reset(); });
            save.addActionListener(event -> save());
            delete.addActionListener(event -> delete());
            importKey.addActionListener(event -> SwingSettingsFileChooser.openAsync(importKey.getText(), false, null).thenAccept(file -> {
                if (file == null || closed || busy) return;
                String projectId = projectId();
                run(() -> service.importKeyFile(file.getAbsolutePath(), projectId), this::fill);
            }));
            reset(); updateLock(); reload(null);
        }

        private JPanel field(String key, JComponent control) {
            JPanel row = new JPanel(new BorderLayout(12, 0));
            JLabel label = new JLabel(i18n.get(key)); label.setPreferredSize(new Dimension(110, 26));
            row.add(label, BorderLayout.WEST); row.add(control);
            GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.gridy = fieldRow++; c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(4, 0, 4, 0);
            fields.add(row, c);
            return row;
        }
        private String projectId() { return project.getSelectedItem() instanceof ProjectProfile p ? p.id() : null; }
        private void updateAuth() {
            boolean key = auth.getSelectedItem() == AuthenticationType.PRIVATE_KEY;
            passwordRow.setVisible(!key); keyRow.setVisible(key); pathRow.setVisible(key); passphraseRow.setVisible(key);
            fields.revalidate(); fields.repaint();
        }
        private void updateLock() {
            var keys = service.getKeyService();
            lockStatus.setText(i18n.get(!keys.isCustomKeyConfigured() ? "vault.status.noMasterPwd" : keys.isUnlocked() ? "vault.status.unlocked" : "vault.status.locked"));
            lock.setText(i18n.get(keys.isUnlocked() ? "vault.action.lock" : keys.isCustomKeyConfigured() ? "vault.action.unlock" : "vault.action.setMasterPwd"));
            hint.setText(encryption.getSelectedItem() == VaultEncryptionMode.CUSTOM
                    ? i18n.get(keys.isUnlocked() ? "vault.hint.customMode" : "vault.hint.needUnlock") : " ");
        }
        private void changeLock() {
            if (busy) return;
            var keys = service.getKeyService();
            if (keys.isUnlocked()) { keys.lock(); reset(); updateLock(); return; }
            boolean setup = !keys.isCustomKeyConfigured();
            new PasswordDialog(null, i18n, themes, i18n.get(setup ? "vault.setMasterPwd.title" : "vault.unlock.title"), setup)
                    .showAsync().thenAccept(secret -> {
                        if (secret == null) return;
                        if (closed || busy) { Arrays.fill(secret, '\0'); return; }
                        run(() -> {
                            try {
                                if (setup) { keys.setupMasterPassword(secret); return true; }
                                return keys.unlock(secret);
                            } finally { Arrays.fill(secret, '\0'); }
                        }, unlocked -> {
                            if (!unlocked) status.setText(i18n.get("vault.unlock.failed"));
                            updateLock(); loadSelected();
                        });
                    });
        }
        private void reload(String selected) {
            long revision = ++loadRevision;
            String scopeId = scope.getSelectedItem() instanceof ProjectProfile p ? p.id() : null;
            CompletableFuture.supplyAsync(() -> service.listByProject(scopeId)).whenComplete((values, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || revision != loadRevision) return;
                if (error != null) { fail(error); return; }
                entries.clear(); entries.addAll(values);
                if (selected != null) values.stream().filter(entry -> selected.equals(entry.id())).findFirst().ifPresent(entry -> list.setSelectedValue(entry, true));
            }));
        }
        private void loadSelected() {
            long revision = ++selectionRevision;
            VaultEntryProfile entry = list.getSelectedValue();
            if (entry == null) { loading = false; updateEnabled(); return; }
            loading = true; updateEnabled();
            clearSecrets();
            name.setText(entry.name()); auth.setSelectedItem(entry.authenticationType()); encryption.setSelectedItem(entry.encryptionMode());
            keyPath.setText(Objects.toString(entry.privateKeyPath(), ""));
            CompletableFuture.supplyAsync(() -> service.loadForm(entry.id())).whenComplete((form, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || revision != selectionRevision) return;
                loading = false; updateEnabled();
                if (error != null) { fail(error); return; }
                fill(form);
            }));
        }
        private void fill(VaultEntryFormData form) {
            name.setText(form.name()); auth.setSelectedItem(form.authenticationType()); encryption.setSelectedItem(form.encryptionMode());
            password.setText(Objects.toString(form.password(), "")); passphrase.setText(Objects.toString(form.passphrase(), ""));
            keyContent.setText(Objects.toString(form.keyContent(), "")); keyPath.setText(Objects.toString(form.privateKeyPath(), ""));
            for (int index = 0; index < project.getItemCount(); index++) if (Objects.equals(project.getItemAt(index).id(), form.projectId())) project.setSelectedIndex(index);
            updateAuth(); updateLock();
        }
        private void reset() {
            selectionRevision++;
            list.clearSelection(); name.setText(""); clearSecrets(); keyPath.setText("");
            auth.setSelectedItem(AuthenticationType.PASSWORD); encryption.setSelectedItem(VaultEncryptionMode.SYSTEM);
            project.setSelectedItem(scope.getSelectedItem()); updateAuth();
        }
        private void save() {
            if (busy || loading || name.getText().isBlank()) return;
            if (encryption.getSelectedItem() == VaultEncryptionMode.CUSTOM && !service.getKeyService().isUnlocked()) {
                status.setText(i18n.get("vault.hint.needUnlock")); return;
            }
            String id = list.getSelectedValue() == null ? null : list.getSelectedValue().id();
            VaultEntryFormData form = new VaultEntryFormData(id, name.getText().strip(), (AuthenticationType) auth.getSelectedItem(),
                    (VaultEncryptionMode) encryption.getSelectedItem(), password(password), password(passphrase), keyContent.getText(), keyPath.getText(), projectId());
            run(() -> service.save(form), saved -> reload(saved.id()));
        }
        private void delete() {
            VaultEntryProfile entry = list.getSelectedValue();
            if (busy || entry == null) return;
            SwingFormDialog.showAsync(i18n.get("vault.manager.title"), "jlshell.vault.delete", i18n,
                    () -> new SwingFormDialog.Form<>(new JLabel(i18n.get("vault.action.deleteConfirm", entry.name())), () -> Boolean.TRUE), 500, 180)
                    .thenAccept(confirmed -> {
                        if (closed || !Boolean.TRUE.equals(confirmed)) return;
                        run(() -> { service.delete(entry.id()); return Boolean.TRUE; }, ignored -> { reset(); reload(null); });
                    });
        }
        private <T> void run(java.util.function.Supplier<T> operation, java.util.function.Consumer<T> completed) {
            if (closed || busy) return;
            busy = true; updateEnabled();
            CompletableFuture.supplyAsync(operation).whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
                busy = false;
                if (closed) return;
                updateEnabled();
                if (error != null) fail(error); else completed.accept(value);
            }));
        }
        private void updateEnabled() {
            setEnabledTree(fields, !busy && !loading);
            save.setEnabled(!busy && !loading);
            delete.setEnabled(!busy);
            lock.setEnabled(!busy);
            scope.setEnabled(!busy);
            list.setEnabled(!busy);
        }
        private static void setEnabledTree(Component component, boolean enabled) {
            component.setEnabled(enabled);
            if (component instanceof Container container) for (Component child : container.getComponents()) setEnabledTree(child, enabled);
        }
        private void fail(Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            status.setText(Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        }
        private void clearSecrets() { password.setText(""); passphrase.setText(""); keyContent.setText(""); }
        @Override public void close() { closed = true; selectionRevision++; clearSecrets(); }
    }

    private static String password(JPasswordField field) {
        char[] secret = field.getPassword();
        try { return new String(secret); }
        finally { Arrays.fill(secret, '\0'); }
    }

    public static final class PasswordDialog {
        private final I18nService i18n;
        private final String title;
        private final boolean confirm;
        public PasswordDialog(Object owner, I18nService i18n, ThemeService themes, String title, boolean confirm) {
            this.i18n = i18n; this.title = title; this.confirm = confirm;
        }
        public Optional<char[]> showNativeAndWait() {
            return Optional.ofNullable(SwingFormDialog.showAndWait(title, "jlshell.vault.password", i18n, this::form, 520, 260));
        }
        public CompletableFuture<char[]> showAsync() {
            return SwingFormDialog.showAsync(title, "jlshell.vault.password", i18n, this::form, 520, 260);
        }
        private SwingFormDialog.Form<char[]> form() {
            class PasswordFields extends JPanel implements AutoCloseable {
                final JPasswordField value = new JPasswordField(), repeated = new JPasswordField();
                PasswordFields() {
                    super(new GridLayout(confirm ? 2 : 1, 2, 10, 10));
                    setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
                    add(new JLabel(i18n.get("vault.field.masterPwd"))); add(value);
                    if (confirm) { add(new JLabel(i18n.get("vault.field.masterPwdConfirm"))); add(repeated); }
                }
                @Override public void close() { value.setText(""); repeated.setText(""); }
            }
            PasswordFields content = new PasswordFields();
            return new SwingFormDialog.Form<>(content, () -> {
                char[] secret = content.value.getPassword(), repeated = content.repeated.getPassword();
                try {
                    if (secret.length > 0 && (!confirm || Arrays.equals(secret, repeated))) return secret;
                    Arrays.fill(secret, '\0'); return null;
                } finally { Arrays.fill(repeated, '\0'); }
            });
        }
    }
}
