package com.jlshell.plugin.api.host;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;

/** Optional host services. Registrations and tasks belong to the calling plugin context. */
public interface HostExtensions {
    record HostInfo(String applicationVersion, String extensionApiVersion) {}
    default HostInfo info() { return new HostInfo("unknown", "1"); }
    enum Capability { TERMINAL, FILE_ACTIONS, FILE_PREVIEWS, TEXT_ACTIONS, FILE_NAVIGATION,
                      VIEW_LAYOUT, TASKS, DIALOGS, SESSION_SELECTION, COMMAND_TRACKING }
    enum Anchor { RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    record FileRef(String sessionId, String path, String name, boolean directory, long size) {
        public boolean remote() { return sessionId != null; }
    }
    record FileSelection(String sessionId, String directory, List<FileRef> files) {
        public FileSelection { files = List.copyOf(files); }
    }
    record FileAction(String id, String title, Icon icon, String group,
                      java.util.function.Predicate<FileSelection> visible,
                      java.util.function.Predicate<FileSelection> enabled,
                      Consumer<FileSelection> execute) {}
    record TextSelection(String sessionId, String text, String directory) {}
    record TextAction(String id, String title, java.util.function.Predicate<TextSelection> visible,
                      Consumer<TextSelection> execute) {}
    record PreviewProvider(String id, String title, java.util.function.Predicate<FileRef> accepts,
                           java.util.function.Function<FileRef, CompletableFuture<JComponent>> create) {}
    record ViewOptions(int preferredWidth, int preferredHeight, int minimumWidth, int minimumHeight, Anchor anchor) {
        public ViewOptions {
            if (preferredWidth < 0 || preferredHeight < 0 || minimumWidth < 0 || minimumHeight < 0)
                throw new IllegalArgumentException("Sizes must be nonnegative logical pixels");
            java.util.Objects.requireNonNull(anchor);
        }
    }
    record Layout(Anchor anchor, int width, int height, boolean visible, boolean maximized) {}
    record Session(String id, String title, String host, String username, boolean connected, boolean active) {}
    interface TaskContext {
        boolean cancelled();
        void progress(double fraction, String message);
    }
    record Availability(boolean available,String reason) {}
    Set<Capability> capabilities();
    default Availability availability(Capability capability) {
        return capabilities().contains(capability) ? new Availability(true,"")
                : new Availability(false,"This host context does not provide "+capability);
    }
    Optional<TerminalAccess> terminal(String sessionId);
    List<Session> sessions();
    CompletableFuture<Void> selectSession(String sessionId);
    Registration onSessionsChanged(Consumer<List<Session>> listener);
    Registration registerFileAction(FileAction action);
    Registration registerTextAction(TextAction action);
    Registration registerPreview(PreviewProvider provider);
    CompletableFuture<Void> openPreview(FileRef file);
    CompletableFuture<Void> navigate(String sessionId, boolean remote, String directory, List<String> selectedPaths);
    void viewOptions(ViewOptions options);
    Optional<Layout> layout();
    Registration onLayoutChanged(Consumer<Layout> listener);
    <T> CompletableFuture<T> task(String title, java.util.function.Function<TaskContext, T> work);
    CompletableFuture<Boolean> confirm(String title, String message);
    CompletableFuture<Optional<String>> prompt(String title, String message, String initialValue);
    CompletableFuture<Optional<String>> chooseFile(String title, boolean save);
    /** Own a registration, subscription or resource until this plugin context is disposed. */
    <T extends AutoCloseable> T own(T resource);

    static HostExtensions unavailable() { return UnavailableHostExtensions.INSTANCE; }
}
