package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.account.AccountService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native account summary and synchronization preferences. */
public final class SwingAccountSettingsPage extends JPanel {
    public enum Action { BROWSER_LOGIN, PASSWORD_LOGIN, REGISTER, CHANGE_PASSWORD }
    public record Values(boolean syncEnabled, String baseUrl) {}
    private final JLabel status = new JLabel(), username = new JLabel(), email = new JLabel(), accountId = new JLabel(), devices = new JLabel();
    private final JButton web, password, register, logout, changePassword;
    private final AccountService accounts;
    private final I18nService i18n;

    public SwingAccountSettingsPage(Values initial, AccountService accounts, I18nService i18n,
                                    Consumer<Values> changed, BiConsumer<Action, Runnable> open, Consumer<String> message) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.i18n = i18n;
        setName("settings.accountForm");
        web = action("account.login.web.action", Action.BROWSER_LOGIN, open);
        password = action("account.login.password.action", Action.PASSWORD_LOGIN, open);
        register = action("account.register.action", Action.REGISTER, open);
        changePassword = action("account.changePassword", Action.CHANGE_PASSWORD, open);
        logout = new JButton(i18n.get("account.logout"));
        logout.addActionListener(event -> {
            logout.setEnabled(false);
            accounts.logout().whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
                refresh();
                message.accept(error == null ? i18n.get("account.logout.success") : error.getMessage());
            }));
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.setOpaque(false);
        for (JButton button : new JButton[]{web, password, register, logout, changePassword}) actions.add(button);
        JPanel profile = new JPanel(new GridBagLayout());
        profile.setOpaque(false);
        addRow(profile, 0, "preferences.account.status", status);
        addRow(profile, 1, "account.username", username);
        addRow(profile, 2, "account.email", email);
        addRow(profile, 3, "preferences.account.accountId", accountId);
        addRow(profile, 4, "preferences.account.deviceCount", devices);
        addRow(profile, 5, null, actions);
        JTextField url = new JTextField(initial.baseUrl());
        url.setName("settings-account-baseUrl");
        JCheckBox sync = new JCheckBox(i18n.get("preferences.account.syncEnabled"), initial.syncEnabled());
        sync.setName("settings-account-sync");
        Runnable publish = () -> changed.accept(new Values(sync.isSelected(), url.getText().isBlank() ? "https://jlshell.com" : url.getText().strip()));
        sync.addActionListener(event -> publish.run());
        url.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { publish.run(); }
            public void removeUpdate(DocumentEvent event) { publish.run(); }
            public void changedUpdate(DocumentEvent event) { publish.run(); }
        });
        JPanel synchronization = new JPanel(new GridBagLayout());
        synchronization.setOpaque(false);
        addRow(synchronization, 0, "preferences.account.baseUrl", url);
        addRow(synchronization, 1, null, sync);
        addRow(synchronization, 2, null, comment(i18n.get("preferences.account.syncHint")));
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(16, 24, 24, 24));
        body.add(section("preferences.account.profileSection", "preferences.account.profileSection.description", profile));
        body.add(Box.createVerticalStrut(28));
        body.add(section("preferences.account.syncSection", "preferences.account.syncSection.description", synchronization));
        JPanel tracking = new JPanel(new BorderLayout());
        tracking.setOpaque(false);
        tracking.add(body, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tracking);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll);
        IdeaFormStyle.applyTree(body);
        refresh();
    }

    private JButton action(String key, Action action, BiConsumer<Action, Runnable> open) {
        JButton button = new JButton(i18n.get(key));
        button.addActionListener(event -> open.accept(action, this::refresh));
        return button;
    }

    private void refresh() {
        var session = accounts == null ? null : accounts.currentSession().orElse(null);
        status.setText(i18n.get(session == null ? "preferences.account.signedOut" : "preferences.account.signedIn"));
        username.setText(session == null || session.username().isBlank() ? "-" : session.username());
        email.setText(session == null || session.email().isBlank() ? "-" : session.email());
        accountId.setText(session == null || session.id().isBlank() ? "-" : session.id());
        devices.setText(session == null ? "-" : Integer.toString(session.historicalDeviceCount()));
        web.setVisible(session == null);
        password.setVisible(session == null);
        register.setVisible(session == null);
        logout.setVisible(session != null);
        changePassword.setVisible(session != null);
        for (JButton button : new JButton[]{web, password, register, logout, changePassword}) button.setEnabled(accounts != null);
        revalidate();
        repaint();
    }

    private void addRow(JPanel form, int row, String label, JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 0, 5, 18);
        form.add(new JLabel(label == null ? "" : i18n.get(label)), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(control, c);
    }

    private JComponent section(String title, String hint, JComponent content) {
        JPanel section = new JPanel(new BorderLayout(0, 12));
        section.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        JLabel heading = new JLabel(i18n.get(title));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 1));
        header.add(heading, BorderLayout.NORTH);
        header.add(comment(i18n.get(hint)));
        section.add(header, BorderLayout.NORTH);
        section.add(content);
        return section;
    }

    private static JTextArea comment(String text) {
        JTextArea hint = new JTextArea(text);
        hint.setEditable(false); hint.setFocusable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true);
        hint.setOpaque(false); hint.setFont(UIManager.getFont("defaultFont"));
        return hint;
    }
}
