package com.jlshell.ui.swing;

import com.jlshell.plugin.api.PluginMetadata;
import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.loader.store.*;
import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Native marketplace and installed-plugin manager. Plugin settings are created lazily on EDT. */
public final class SwingPluginSettingsPage extends JPanel implements AutoCloseable {
    public record Entry(PluginMetadata metadata, boolean enabled, PluginIcons.Source icon, Supplier<JComponent> settings) {}
    public interface Runtime {
        CompletableFuture<List<Entry>> entries();
        CompletableFuture<Void> enable(Entry entry, boolean enabled);
        CompletableFuture<Void> deactivate(String id);
        CompletableFuture<Void> reload();
    }
    private final I18nService i18n;
    private final Runtime runtime;
    private final String hostVersion;
    private final PluginStoreClient client;
    private final PluginInstaller installer;
    private final JPanel cards = new JPanel(new CardLayout());
    private final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JToggleButton installedTab;
    private final DefaultListModel<Entry> installedModel = new DefaultListModel<>();
    private final DefaultListModel<PluginStoreListing> marketModel = new DefaultListModel<>();
    private final JList<Entry> installedList = new JList<>(installedModel);
    private final JList<PluginStoreListing> marketList = new JList<>(marketModel);
    private final JTextField installedSearch = new JTextField(), marketSearch = new JTextField();
    private final JComboBox<String> installedScope, marketScope;
    private final JPanel installedDetail = new JPanel(new BorderLayout()), marketDetail = new JPanel(new BorderLayout());
    private final JLabel installedStatus = new JLabel(" "), marketStatus = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton search, updates;
    private final Map<String, JComponent> settingsPages = new HashMap<>();
    private List<Entry> installed = List.of();
    private boolean closed, operating;
    private long searchRevision, detailRevision;

    public SwingPluginSettingsPage(String baseUrl, String hostVersion, I18nService i18n, Runtime runtime) {
        super(new BorderLayout());
        this.i18n = i18n;
        this.runtime = runtime;
        this.hostVersion = hostVersion;
        client = new PluginStoreClient(baseUrl, java.util.concurrent.ForkJoinPool.commonPool());
        Path home = Path.of(System.getProperty("user.home"), ".jlshell");
        installer = new PluginInstaller(client, home.resolve("program-plugins"), home.resolve("plugins"));
        installedScope = scopes();
        marketScope = scopes();
        search = new JButton(i18n.get("plugins.store.searchButton"));
        updates = new JButton(i18n.get("plugins.store.checkUpdates"));
        setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
        installedList.setFixedCellHeight(84);
        marketList.setFixedCellHeight(84);
        installedList.setCellRenderer((list, entry, index, selected, focused) -> cell(list, entry.metadata().displayName(),
                entry.metadata().id(), entry.metadata().scope() + " · " + entry.metadata().version() + " · "
                        + i18n.get(entry.enabled() ? "plugins.store.enabled" : "plugins.store.disabled"), entry.icon(), selected));
        marketList.setCellRenderer((list, entry, index, selected, focused) -> cell(list, entry.displayName(),
                entry.description(), entry.scope() + " · " + entry.latestVersion() + " · ↓ " + entry.downloads()
                        + (isInstalled(entry) ? " · " + i18n.get("plugins.store.installedBadge") : ""), PluginIcons.remote(entry.iconUrl()), selected));
        marketSearch.putClientProperty("JTextField.placeholderText", i18n.get("plugins.store.search"));
        installedSearch.putClientProperty("JTextField.placeholderText", i18n.get("plugins.store.searchInstalled"));
        progress.setVisible(false);
        marketDetail.add(empty("plugins.store.selectHint"));
        installedDetail.add(empty("plugins.store.selectInstalledHint"));
        cards.add(workspace(marketSearch, marketScope, search, marketList, marketDetail, marketStatus, progress), "market");
        cards.add(workspace(installedSearch, installedScope, updates, installedList, installedDetail, installedStatus, null), "installed");
        JToggleButton marketTab = new JToggleButton(i18n.get("plugins.store.marketplace"), true);
        installedTab = new JToggleButton(i18n.get("plugins.store.installed", 0));
        marketTab.setName("settings.pluginTab.0");
        installedTab.setName("settings.pluginTab.1");
        ButtonGroup group = new ButtonGroup();
        group.add(marketTab); group.add(installedTab);
        marketTab.addActionListener(event -> ((CardLayout) cards.getLayout()).show(cards, "market"));
        installedTab.addActionListener(event -> ((CardLayout) cards.getLayout()).show(cards, "installed"));
        header.setOpaque(false);
        header.add(marketTab); header.add(installedTab);
        putClientProperty("jlshell.header", header);
        add(cards);
        search.addActionListener(event -> search());
        marketSearch.addActionListener(event -> search());
        marketScope.addActionListener(event -> search());
        installedScope.addActionListener(event -> filterInstalled());
        installedSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filterInstalled(); }
            public void removeUpdate(DocumentEvent event) { filterInstalled(); }
            public void changedUpdate(DocumentEvent event) { filterInstalled(); }
        });
        marketList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showMarket(marketList.getSelectedValue()); });
        installedList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) showInstalled(installedList.getSelectedValue()); });
        updates.addActionListener(event -> checkUpdates());
        IdeaFormStyle.applyTree(this);
        refreshInstalled();
        search();
    }

    private JComboBox<String> scopes() {
        return new JComboBox<>(new String[]{i18n.get("plugins.filter.all"), i18n.get("plugins.filter.program"), i18n.get("plugins.filter.session")});
    }
    private PluginScope selectedScope(JComboBox<?> scope) {
        return switch (scope.getSelectedIndex()) { case 1 -> PluginScope.PROGRAM; case 2 -> PluginScope.SESSION; default -> null; };
    }
    private JComponent workspace(JTextField query, JComboBox<String> scope, JButton action, JList<?> list,
                                 JPanel detail, JLabel status, JProgressBar progressBar) {
        JPanel controls = new JPanel(new BorderLayout(6, 6));
        controls.add(query, BorderLayout.NORTH);
        JPanel filters = new JPanel(new BorderLayout(6, 0));
        filters.add(scope);
        filters.add(action, BorderLayout.EAST);
        controls.add(filters);
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setMinimumSize(new Dimension(230, 100));
        left.add(controls, BorderLayout.NORTH);
        left.add(new JScrollPane(list));
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.add(status, BorderLayout.SOUTH);
        if (progressBar != null) footer.add(progressBar);
        left.add(footer, BorderLayout.SOUTH);
        detail.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail);
        split.setResizeWeight(.4);
        split.setDividerLocation(330);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent cell(JList<?> list, String name, String description, String metadata, PluginIcons.Source icon, boolean selected) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        panel.add(new JLabel(PluginIcons.icon(name, icon, 40, false)), BorderLayout.WEST);
        JPanel text = new JPanel(new GridLayout(3, 1, 0, 3));
        text.setOpaque(false);
        for (String value : new String[]{name, description, metadata}) {
            JLabel label = new JLabel(Objects.toString(value, ""));
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            text.add(label);
        }
        panel.add(text);
        return panel;
    }

    private void search() {
        long revision = ++searchRevision;
        search.setEnabled(false);
        marketStatus.setText(i18n.get("plugins.store.loading"));
        client.search(new PluginStoreSearch(marketSearch.getText(), selectedScope(marketScope), hostVersion, i18n.getLocale(), 0, 20, PluginStoreSearch.Sort.UPDATED))
                .whenComplete((page, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed || revision != searchRevision) return;
                    search.setEnabled(true);
                    if (error != null) { fail(marketStatus, error); return; }
                    marketModel.clear();
                    marketModel.addAll(page.content());
                    marketStatus.setText(i18n.get("plugins.store.results", page.totalElements()));
                    if (!marketModel.isEmpty()) marketList.setSelectedIndex(0);
                }));
    }

    private void refreshInstalled() {
        runtime.entries().whenComplete((entries, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) { fail(installedStatus, error); return; }
            closeSettingsPages();
            installed = List.copyOf(entries);
            installedTab.setText(i18n.get("plugins.store.installed", entries.size()));
            filterInstalled();
            marketList.repaint();
        }));
    }

    private void filterInstalled() {
        if (closed) return;
        Entry selected = installedList.getSelectedValue();
        String query = installedSearch.getText().strip().toLowerCase(Locale.ROOT);
        PluginScope scope = selectedScope(installedScope);
        installedModel.clear();
        installedModel.addAll(installed.stream().filter(entry -> (scope == null || scope == entry.metadata().scope())
                && (query.isBlank() || (entry.metadata().displayName() + " " + entry.metadata().id()).toLowerCase(Locale.ROOT).contains(query))).toList());
        if (selected != null) installedList.setSelectedValue(selected, true);
        if (installedList.getSelectedIndex() < 0 && !installedModel.isEmpty()) installedList.setSelectedIndex(0);
    }

    private void showMarket(PluginStoreListing listing) {
        long revision = ++detailRevision;
        if (listing == null) { replace(marketDetail, empty("plugins.store.selectHint")); return; }
        replace(marketDetail, empty("plugins.store.loading"));
        client.detail(listing.pluginId(), i18n.getLocale(), hostVersion).whenComplete((detail, error) -> SwingUtilities.invokeLater(() -> {
            if (closed || revision != detailRevision) return;
            if (error != null) { fail(marketStatus, error); return; }
            PluginStoreVersion version = detail == null ? null : detail.versions().stream().filter(PluginStoreVersion::approved)
                    .filter(item -> item.version().equals(listing.latestVersion())).findFirst().orElse(null);
            JPanel body = new JPanel(new BorderLayout(0, 16));
            JButton install = new JButton(i18n.get("plugins.store.install"));
            install.setEnabled(version != null && !operating);
            install.addActionListener(event -> install(listing, version, install));
            body.add(heading(listing.displayName(), listing.author() + " · " + listing.scope(), install), BorderLayout.NORTH);
            JTabbedPane details = new JTabbedPane();
            details.addTab(i18n.get("plugins.store.overview"), text(listing.description() + "\n\n" + i18n.get("plugins.field.version") + "\n"
                    + (version == null ? i18n.get("plugins.store.noApprovedVersion") : version.version() + " · " + humanSize(version.size()))));
            details.addTab(i18n.get("plugins.store.releaseNotes"), text(version == null ? "—" : version.releaseNotes()));
            details.addTab(i18n.get("plugins.store.additionalInfo"), text(listing.pluginId()));
            body.add(details);
            replace(marketDetail, body);
        }));
    }

    private void showInstalled(Entry entry) {
        if (entry == null) { replace(installedDetail, empty("plugins.store.selectInstalledHint")); return; }
        JPanel body = new JPanel(new BorderLayout(0, 16));
        JButton toggle = new JButton(i18n.get(entry.enabled() ? "plugins.store.disable" : "plugins.store.enable"));
        toggle.setEnabled(!operating);
        toggle.addActionListener(event -> confirm(entry.enabled(), i18n.get("plugins.store.disableTitle"),
                i18n.get("plugins.store.disableMessage", entry.metadata().displayName()), i18n.get("plugins.store.disable"), () -> {
                    if (operating) return;
                    operating = true;
                    toggle.setEnabled(false);
                    runtime.enable(entry, !entry.enabled()).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                        operating = false;
                        if (closed) return;
                        if (error != null) { fail(installedStatus, error); toggle.setEnabled(true); }
                        else { refreshInstalled(); installedStatus.setText(i18n.get(entry.enabled() ? "plugins.store.disabledMessage" : "plugins.store.enabledMessage", entry.metadata().displayName())); }
                    }));
                }));
        JButton uninstall = new JButton(i18n.get("plugins.store.uninstall"));
        uninstall.setEnabled(false);
        CompletableFuture.supplyAsync(() -> installer.findInstalled(entry.metadata().id()).stream().anyMatch(item -> item.scope() == entry.metadata().scope()))
                .thenAccept(removable -> SwingUtilities.invokeLater(() -> { if (!closed) uninstall.setEnabled(removable && !operating); }));
        uninstall.addActionListener(event -> confirm(true, i18n.get("plugins.store.uninstallTitle"),
                i18n.get("plugins.store.uninstallMessage", entry.metadata().displayName()), i18n.get("plugins.store.uninstall"), () -> uninstall(entry)));
        body.add(heading(entry.metadata().displayName(), entry.metadata().author() + " · " + entry.metadata().scope(), toggle, uninstall), BorderLayout.NORTH);
        JTabbedPane detailTabs = new JTabbedPane();
        JPanel overview = new JPanel(new BorderLayout(0, 12));
        overview.add(text(entry.metadata().description() + "\n\n" + Objects.toString(entry.metadata().compatibilityWarning(), "")), BorderLayout.NORTH);
        if (entry.enabled() && entry.settings() != null) {
            String key = entry.metadata().scope() + ":" + entry.metadata().id();
            JComponent page = settingsPages.computeIfAbsent(key, ignored -> settings(entry));
            overview.add(page);
        }
        detailTabs.addTab(i18n.get("plugins.store.overview"), overview);
        PluginMetadata metadata = entry.metadata();
        detailTabs.addTab(i18n.get("plugins.store.additionalInfo"), text(metadata.id() + "\n" + metadata.version() + "\n"
                + metadata.minHostVersionInclusive() + " - " + metadata.maxHostVersionInclusive() + "\n" + metadata.compatibilityStatus().name()));
        body.add(detailTabs);
        replace(installedDetail, body);
        IdeaFormStyle.applyTree(body);
    }

    private JComponent settings(Entry entry) {
        try { return Objects.requireNonNull(entry.settings().get()); }
        catch (RuntimeException | LinkageError error) {
            org.slf4j.LoggerFactory.getLogger(SwingPluginSettingsPage.class).warn("Cannot create settings for plugin {}", entry.metadata().id(), error);
            return text(i18n.get("plugins.settingsUnavailableMessage", entry.metadata().displayName()));
        }
    }

    private void install(PluginStoreListing listing, PluginStoreVersion version, JButton button) {
        if (operating) return;
        operating = true;
        button.setEnabled(false);
        CompletableFuture.supplyAsync(() -> installer.findInstalled(listing.pluginId())).whenComplete((existing, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) { operating = false; return; }
            if (error != null) { operating = false; button.setEnabled(true); fail(marketStatus, error); return; }
            if (installed.stream().anyMatch(entry -> entry.metadata().id().equals(listing.pluginId())) && existing.isEmpty()) {
                operating = false; button.setEnabled(true); marketStatus.setText(i18n.get("plugins.store.bundledConflict")); return;
            }
            if (existing.stream().anyMatch(item -> item.scope() != listing.scope())) {
                operating = false; button.setEnabled(true); marketStatus.setText(i18n.get("plugins.store.scopeConflict")); return;
            }
            String previous = existing.stream().map(PluginInstaller.InstalledArtifact::version).filter(Objects::nonNull).distinct().collect(java.util.stream.Collectors.joining(", "));
            confirm(!existing.isEmpty(), i18n.get("plugins.store.replaceTitle"),
                    i18n.get("plugins.store.replaceMessage", listing.displayName(), previous, version.version()), i18n.get("plugins.store.replace"),
                    accepted -> {
                        if (!accepted || closed) { operating = false; button.setEnabled(true); return; }
                        progress.setVisible(true);
                        progress.setValue(0);
                        marketStatus.setText(i18n.get("plugins.store.installing"));
                        CompletableFuture.supplyAsync(() -> installer.install(listing, version,
                                () -> { if (listing.scope() == PluginScope.SESSION) runtime.deactivate(listing.pluginId()).join(); },
                                value -> SwingUtilities.invokeLater(() -> { if (!closed) progress.setValue((int) Math.round(value * 100)); })))
                                .thenCompose(result -> listing.scope() == PluginScope.SESSION ? runtime.reload() : CompletableFuture.completedFuture(null))
                                .whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                                    operating = false;
                                    if (closed) return;
                                    progress.setVisible(false);
                                    button.setEnabled(true);
                                    if (failure != null) fail(marketStatus, failure);
                                    else { refreshInstalled(); marketStatus.setText(i18n.get(listing.scope() == PluginScope.SESSION ? "plugins.store.installedSession" : "plugins.store.installedProgram")); }
                                }));
                    });
        }));
    }

    private void uninstall(Entry entry) {
        if (operating) return;
        operating = true;
        installedStatus.setText(i18n.get("plugins.store.uninstalling"));
        CompletableFuture.supplyAsync(() -> installer.uninstall(entry.metadata().id(), entry.metadata().scope(),
                () -> { if (entry.metadata().scope() == PluginScope.SESSION) runtime.deactivate(entry.metadata().id()).join(); }))
                .thenCompose(result -> {
                    if (!result.removed()) throw new IllegalStateException(i18n.get("plugins.store.notRemovable"));
                    return entry.metadata().scope() == PluginScope.SESSION ? runtime.reload() : CompletableFuture.completedFuture(null);
                }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                    operating = false;
                    if (closed) return;
                    if (error != null) { fail(installedStatus, error); return; }
                    installed = installed.stream().filter(item -> item.metadata().scope() != entry.metadata().scope() || !item.metadata().id().equals(entry.metadata().id())).toList();
                    closeSettingsPages();
                    filterInstalled();
                    installedTab.setText(i18n.get("plugins.store.installed", installed.size()));
                    installedStatus.setText(i18n.get(entry.metadata().scope() == PluginScope.SESSION ? "plugins.store.uninstalledSession" : "plugins.store.uninstalledProgram"));
                }));
    }

    private void checkUpdates() {
        if (installed.isEmpty()) { installedStatus.setText(i18n.get("plugins.store.noInstalled")); return; }
        updates.setEnabled(false);
        installedStatus.setText(i18n.get("plugins.store.checkingUpdates"));
        var checks = installed.stream().map(entry -> client.latestUpdate(entry.metadata().id(), entry.metadata().version(), hostVersion)).toList();
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            updates.setEnabled(true);
            if (error != null) { fail(installedStatus, error); return; }
            long count = checks.stream().map(CompletableFuture::join).filter(PluginStoreUpdate::updateAvailable).count();
            installedStatus.setText(i18n.get(count == 0 ? "plugins.store.upToDate" : "plugins.store.updatesAvailable", count));
        }));
    }
    private boolean isInstalled(PluginStoreListing listing) {
        return installed.stream().anyMatch(entry -> entry.metadata().scope() == listing.scope() && entry.metadata().id().equals(listing.pluginId()));
    }
    private void confirm(boolean needed, String title, String message, String action, Runnable accepted) {
        confirm(needed, title, message, action, value -> { if (value && !closed) accepted.run(); });
    }
    private void confirm(boolean needed, String title, String message, String action, java.util.function.Consumer<Boolean> accepted) {
        if (!needed) { accepted.accept(true); return; }
        SwingFormDialog.showAsync(title, "jlshell.plugins.confirm", i18n,
                () -> new SwingFormDialog.Form<>(text(message), () -> Boolean.TRUE, action), 580, 240)
                .thenAccept(value -> accepted.accept(Boolean.TRUE.equals(value)));
    }
    private JPanel heading(String title, String subtitle, JButton... actions) {
        JPanel heading = new JPanel(new BorderLayout(0, 10));
        JPanel labels = new JPanel(new GridLayout(2, 1, 0, 4));
        JLabel name = new JLabel(title);
        name.setFont(name.getFont().deriveFont(Font.BOLD, name.getFont().getSize2D() + 6));
        labels.add(name);
        labels.add(new JLabel(subtitle));
        heading.add(labels, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JButton button : actions) buttons.add(button);
        heading.add(buttons);
        return heading;
    }
    private JComponent empty(String key) { return text(i18n.get(key)); }
    private static JComponent text(String value) {
        JTextArea text = new JTextArea(Objects.toString(value, ""));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }
    private void fail(JLabel label, Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        label.setText(i18n.get("plugins.store.error", Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
    }
    private static String humanSize(long bytes) { return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d)); }
    private static void replace(JPanel parent, JComponent content) {
        parent.removeAll(); parent.add(content); parent.revalidate(); parent.repaint();
    }
    private void closeSettingsPages() {
        settingsPages.values().forEach(page -> {
            if (page instanceof AutoCloseable resource) try { resource.close(); }
            catch (Exception error) { org.slf4j.LoggerFactory.getLogger(SwingPluginSettingsPage.class).warn("Cannot close plugin settings", error); }
        });
        settingsPages.clear();
    }
    @Override public void close() { closed = true; closeSettingsPages(); }
}
