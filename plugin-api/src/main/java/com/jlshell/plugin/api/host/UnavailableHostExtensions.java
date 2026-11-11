package com.jlshell.plugin.api.host;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

final class UnavailableHostExtensions implements HostExtensions {
    static final HostExtensions INSTANCE = new UnavailableHostExtensions();
    private <T> CompletableFuture<T> unavailable() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Host extensions unavailable"));
    }
    public Set<Capability> capabilities() { return Set.of(); }
    public Optional<TerminalAccess> terminal(String id) { return Optional.empty(); }
    public List<Session> sessions() { return List.of(); }
    public CompletableFuture<Void> selectSession(String id) { return unavailable(); }
    public Registration onSessionsChanged(Consumer<List<Session>> listener) { return Registration.noop(); }
    public Registration registerFileAction(FileAction action) { throw new UnsupportedOperationException("File actions unavailable"); }
    public Registration registerTextAction(TextAction action) { throw new UnsupportedOperationException("Text actions unavailable"); }
    public Registration registerPreview(PreviewProvider provider) { throw new UnsupportedOperationException("Previews unavailable"); }
    public CompletableFuture<Void> openPreview(FileRef file) { return unavailable(); }
    public CompletableFuture<Void> navigate(String session, boolean remote, String dir, List<String> selection) { return unavailable(); }
    public void viewOptions(ViewOptions options) { throw new UnsupportedOperationException("Layout unavailable"); }
    public Optional<Layout> layout() { return Optional.empty(); }
    public Registration onLayoutChanged(Consumer<Layout> listener) { return Registration.noop(); }
    public <T> CompletableFuture<T> task(String title, Function<TaskContext,T> work) { return unavailable(); }
    public CompletableFuture<Boolean> confirm(String title, String message) { return unavailable(); }
    public CompletableFuture<Optional<String>> prompt(String title,String message,String initial) { return unavailable(); }
    public CompletableFuture<Optional<String>> chooseFile(String title,boolean save) { return unavailable(); }
    public <T extends AutoCloseable> T own(T resource) { throw new UnsupportedOperationException("Lifecycle unavailable"); }
}
