package com.jlshell.ui.view;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.jlshell.ui.model.FavoriteConnectionProfile;
import com.jlshell.ui.model.RecentSessionProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Welcome panel shown in the workspace area when no session tabs are open.
 * Two-column layout: left = recent connections, right = favorites.
 * Both columns scroll independently and grow with the window height.
 */
public class WelcomePane extends VBox {

    private final I18nService i18n;
    private final ConnectionProfileService connectionProfileService;
    private final ExecutorService executor;
    private final Consumer<String> onConnectById;

    private VBox recentContainer;
    private VBox favoritesContainer;
    private ScrollPane recentScrollPane;
    private ScrollPane favScrollPane;
    private Runnable onCreateConnection;
    private Runnable onCreateFolder;

    public WelcomePane(I18nService i18n, ConnectionProfileService connectionProfileService,
                       ExecutorService executor, Runnable onCreateConnection,
                       Runnable onCreateFolder, Consumer<String> onConnectById) {
        this.i18n = i18n;
        this.connectionProfileService = connectionProfileService;
        this.executor = executor;
        this.onCreateConnection = onCreateConnection;
        this.onCreateFolder = onCreateFolder;
        this.onConnectById = onConnectById;

        setSpacing(0);
        setPadding(new Insets(40));
        getStyleClass().add("welcome-pane");

        buildContent();
        refresh();
    }

    private void buildContent() {
        // App icon
        ImageView iconView = null;
        var iconUrl = WelcomePane.class.getResource("/icons/app_icon.png");
        if (iconUrl != null) {
            Image icon = new Image(iconUrl.toExternalForm(), 64, 64, true, true);
            iconView = new ImageView(icon);
        }

        // Title & subtitle
        Label title = new Label(i18n.get("welcome.title"));
        title.getStyleClass().add("welcome-title");

        Label subtitle = new Label(i18n.get("welcome.subtitle"));
        subtitle.getStyleClass().add("welcome-subtitle");

        VBox header = new VBox(6, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // Quick start label & buttons
        Label quickStart = new Label(i18n.get("welcome.quickStart"));
        quickStart.getStyleClass().add("welcome-section-label");

        Button newConnBtn = new Button(i18n.get("action.newConnection"));
        newConnBtn.getStyleClass().add("action-btn");
        newConnBtn.setOnAction(e -> onCreateConnection.run());

        Button newFolderBtn = new Button(i18n.get("sidebar.newFolder"));
        newFolderBtn.getStyleClass().add("action-btn-secondary");
        newFolderBtn.setOnAction(e -> onCreateFolder.run());

        HBox actions = new HBox(12, newConnBtn, newFolderBtn);
        actions.setAlignment(Pos.CENTER);
        actions.getStyleClass().add("welcome-actions");

        // ── Two-column grid: Recent (left) | Favorites (right) ──

        Label recentLabel = new Label(i18n.get("welcome.recentConnections"));
        recentLabel.getStyleClass().add("welcome-section-label");

        recentContainer = new VBox(4);
        recentContainer.getStyleClass().add("welcome-section-content");

        recentScrollPane = new ScrollPane(recentContainer);
        recentScrollPane.getStyleClass().add("welcome-scroll-pane");
        recentScrollPane.setFitToWidth(true);
        recentScrollPane.setFitToHeight(true);

        Label favLabel = new Label(i18n.get("welcome.favorites"));
        favLabel.getStyleClass().add("welcome-section-label");

        favoritesContainer = new VBox(4);
        favoritesContainer.getStyleClass().add("welcome-section-content");

        favScrollPane = new ScrollPane(favoritesContainer);
        favScrollPane.getStyleClass().add("welcome-scroll-pane");
        favScrollPane.setFitToWidth(true);
        favScrollPane.setFitToHeight(true);

        VBox recentCol = new VBox(6, recentLabel, recentScrollPane);
        VBox favCol = new VBox(6, favLabel, favScrollPane);
        // Let the scroll panes grow to fill available column height
        VBox.setVgrow(recentScrollPane, Priority.ALWAYS);
        VBox.setVgrow(favScrollPane, Priority.ALWAYS);

        GridPane columns = new GridPane();
        columns.getStyleClass().add("welcome-columns");
        columns.setHgap(24);
        ColumnConstraints left = new ColumnConstraints();
        left.setHgrow(Priority.ALWAYS);
        left.setPercentWidth(50);
        ColumnConstraints right = new ColumnConstraints();
        right.setHgrow(Priority.ALWAYS);
        right.setPercentWidth(50);
        columns.getColumnConstraints().addAll(left, right);
        columns.add(recentCol, 0, 0);
        columns.add(favCol, 1, 0);

        // Help section
        Label shortcutsLabel = new Label(i18n.get("welcome.help.shortcuts"));
        shortcutsLabel.getStyleClass().add("welcome-section-label");

        String mod = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘" : "Ctrl";

        VBox helpItems = new VBox(6);
        helpItems.getStyleClass().add("welcome-help");
        helpItems.getChildren().addAll(
                shortcutLabel(i18n.get("welcome.help.newConnection", mod)),
                shortcutLabel(i18n.get("welcome.help.refreshConnections", mod)),
                shortcutLabel(i18n.get("welcome.help.doubleClick")),
                shortcutLabel(i18n.get("welcome.help.rightClick"))
        );

        // ── Assemble ──
        // Top section (fixed): icon + header + buttons
        VBox topSection = new VBox(12);
        topSection.setAlignment(Pos.CENTER);
        if (iconView != null) {
            topSection.getChildren().add(iconView);
        }
        topSection.getChildren().addAll(header, new Separator(), quickStart, actions);

        // Middle section (grows): two-column lists
        VBox middleSection = new VBox(8, new Separator(), columns);
        VBox.setVgrow(columns, Priority.ALWAYS);

        // Bottom section (fixed): shortcuts
        VBox bottomSection = new VBox(8, new Separator(), shortcutsLabel, helpItems);

        VBox content = new VBox(16);
        content.setAlignment(Pos.TOP_CENTER);
        content.setMaxWidth(640);
        content.getChildren().addAll(topSection, middleSection, bottomSection);
        VBox.setVgrow(middleSection, Priority.ALWAYS);

        getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);
    }

    public void refresh() {
        CompletableFuture.supplyAsync(() -> connectionProfileService.listRecentSessions(10), executor)
                .thenAccept(sessions -> FxThread.run(() -> populateRecentSessions(sessions)));
        CompletableFuture.supplyAsync(() -> connectionProfileService.listFavoriteProfiles(), executor)
                .thenAccept(favorites -> FxThread.run(() -> populateFavorites(favorites)));
    }

    private void populateRecentSessions(List<RecentSessionProfile> sessions) {
        recentContainer.getChildren().clear();
        if (sessions.isEmpty()) {
            Label empty = new Label(i18n.get("welcome.noRecentConnections"));
            empty.getStyleClass().add("welcome-empty-label");
            recentContainer.getChildren().add(empty);
            return;
        }
        for (RecentSessionProfile session : sessions) {
            recentContainer.getChildren().add(buildRecentRow(session));
        }
    }

    private void populateFavorites(List<FavoriteConnectionProfile> favorites) {
        favoritesContainer.getChildren().clear();
        if (favorites.isEmpty()) {
            Label empty = new Label(i18n.get("welcome.noFavorites"));
            empty.getStyleClass().add("welcome-empty-label");
            favoritesContainer.getChildren().add(empty);
            return;
        }
        for (FavoriteConnectionProfile fav : favorites) {
            favoritesContainer.getChildren().add(buildFavoriteRow(fav));
        }
    }

    private HBox buildRecentRow(RecentSessionProfile session) {
        VBox info = new VBox(2);
        Label name = new Label(session.displayName());
        name.getStyleClass().add("welcome-conn-name");
        Label summary = new Label(session.summary());
        summary.getStyleClass().add("welcome-conn-summary");
        info.getChildren().addAll(name, summary);

        Label time = new Label(formatTimeAgo(session.openedAt()));
        time.getStyleClass().add("welcome-conn-time");

        HBox row = new HBox(8, info, time);
        row.getStyleClass().add("welcome-connection-row");
        HBox.setHgrow(info, Priority.ALWAYS);
        row.setOnMouseClicked(e -> onConnectById.accept(session.connectionId()));
        return row;
    }

    private HBox buildFavoriteRow(FavoriteConnectionProfile fav) {
        VBox info = new VBox(2);
        Label name = new Label(fav.displayName());
        name.getStyleClass().add("welcome-conn-name");

        String folderText = fav.folderPath() != null
                ? i18n.get("welcome.folderPath", fav.folderPath())
                : i18n.get("welcome.noFolder");
        Label folder = new Label(folderText);
        folder.getStyleClass().add("welcome-conn-folder");

        Label hostInfo = new Label(fav.hostInfo());
        hostInfo.getStyleClass().add("welcome-conn-summary");

        info.getChildren().addAll(name, folder, hostInfo);

        HBox row = new HBox(info);
        row.getStyleClass().add("welcome-connection-row");
        row.setOnMouseClicked(e -> onConnectById.accept(fav.id()));
        return row;
    }

    private String formatTimeAgo(Instant time) {
        if (time == null) return "";
        Duration dur = Duration.between(time, Instant.now());
        long minutes = dur.toMinutes();
        if (minutes < 1) return i18n.get("welcome.timeAgo.justNow");
        if (minutes < 60) return i18n.get("welcome.timeAgo.minutesAgo", minutes);
        long hours = dur.toHours();
        if (hours < 24) return i18n.get("welcome.timeAgo.hoursAgo", hours);
        long days = dur.toDays();
        return i18n.get("welcome.timeAgo.daysAgo", days);
    }

    private static Label shortcutLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("welcome-help-item");
        return label;
    }
}
