package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.host.HostExtensions;
import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Workbench-owned endpoints and plugin-owned contributions. No UI component is retained after unregister. */
public final class HostExtensionRegistry {
    private HostExtensionRegistry() {}
    private static volatile com.jlshell.program.api.ProgramWorkspaceService workspace=com.jlshell.program.api.ProgramWorkspaceService.unavailable();
    private static volatile WorkspaceUi workspaceUi;
    public static void workspace(com.jlshell.program.api.ProgramWorkspaceService value) { workspace=Objects.requireNonNull(value); }
    public static com.jlshell.program.api.ProgramWorkspaceService workspace() { return workspace; }
    public static void workspaceUi(WorkspaceUi value) { workspaceUi=value; }
    public static WorkspaceUi workspaceUi() {
        if(workspaceUi==null) throw new IllegalStateException("Desktop workbench is not ready");
        return workspaceUi;
    }
    public record WorkspaceChange(String type, String sessionId, String relatedId) {}
    private static final CopyOnWriteArrayList<Consumer<WorkspaceChange>> workspaceListeners = new CopyOnWriteArrayList<>();
    public static Registration onWorkspaceChange(Consumer<WorkspaceChange> listener) {
        workspaceListeners.add(listener);
        return () -> workspaceListeners.remove(listener);
    }
    public static void reconnected(String previousSessionId, String sessionId) {
        for (var listener : workspaceListeners) {
            try { listener.accept(new WorkspaceChange("RECONNECTED", sessionId, previousSessionId)); }
            catch (RuntimeException error) { report(error); }
        }
    }
    public record Binding(String connectionId,com.jlshell.program.api.ProgramWorkspaceService.Presentation presentation) {}
    private static final ConcurrentMap<String,Binding> bindings=new ConcurrentHashMap<>();
    public static void bind(String id,String connectionId,com.jlshell.program.api.ProgramWorkspaceService.Presentation presentation) { bindings.put(id,new Binding(connectionId,presentation)); }
    public static Optional<Binding> binding(String id) { return Optional.ofNullable(bindings.get(id)); }
    public static void unbind(String id) { bindings.remove(id); }
    public record TerminalEntry(String sessionId,String terminalId,TerminalAccess access) {}
    private static final ConcurrentMap<String,TerminalEntry> terminalEntries=new ConcurrentHashMap<>();
    public static List<TerminalEntry> terminalEntries(String sessionId) {
        return terminalEntries.values().stream().filter(entry -> entry.sessionId().equals(sessionId)).sorted(Comparator.comparing(TerminalEntry::terminalId)).toList();
    }
    public static TerminalAccess resolveTerminal(String sessionId,String terminalId) {
        if(terminalId!=null && !terminalId.isBlank()) {
            var entry=terminalEntries.get(terminalId);
            if(entry==null || !entry.sessionId().equals(sessionId)) throw new IllegalArgumentException("Terminal does not belong to the requested session");
            return entry.access();
        }
        var entries=terminalEntries(sessionId);
        if(entries.size()!=1) throw new IllegalStateException("Specify terminalId when the session has zero or multiple terminals");
        return entries.get(0).access();
    }

    private static volatile String applicationVersion="unknown";
    public static void applicationVersion(String value) { applicationVersion=Objects.requireNonNull(value); }
    public static String applicationVersion() { return applicationVersion; }
    public record Task(String id,String pluginId,String title,double progress,String message,Runnable cancel) {}
    private static final ConcurrentMap<String,Task> tasks=new ConcurrentHashMap<>();
    static void task(Task task) { tasks.put(task.id(),task); }
    static void progressTask(Task task) { tasks.computeIfPresent(task.id(),(id,existing) -> task); }
    static void finishTask(String id) { tasks.remove(id); }
    public static List<Task> tasks(String pluginId) { return tasks.values().stream().filter(task -> task.pluginId().equals(pluginId)).toList(); }
    public static List<Task> tasks() { return List.copyOf(tasks.values()); }

    public record Owned<T>(String sessionId, T value) {}
    private static final ConcurrentMap<String, TerminalAccess> terminals = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Owned<HostExtensions.FileAction>> fileActions = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Owned<HostExtensions.TextAction>> textActions = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Owned<HostExtensions.PreviewProvider>> previews = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Consumer<List<HostExtensions.Session>>> sessionListeners = new CopyOnWriteArrayList<>();
    private static final ConcurrentMap<String, HostExtensions.Session> sessions = new ConcurrentHashMap<>();
    public interface FileBrowser {
        CompletableFuture<Void> navigate(boolean remote, String directory, List<String> selection);
        CompletableFuture<Void> preview(HostExtensions.FileRef file);
    }
    private static final ConcurrentMap<String, FileBrowser> browsers = new ConcurrentHashMap<>();
    private static volatile Function<String, CompletableFuture<Void>> sessionSelector;
    public static void disconnected(String id) {
        sessions.computeIfPresent(id,(key,value) -> new HostExtensions.Session(value.id(),value.title(),value.host(),value.username(),false,value.active()));
        publishSessions();
    }
    public static void activeSession(String id) {
        sessions.replaceAll((key,value) -> new HostExtensions.Session(value.id(),value.title(),value.host(),value.username(),value.connected(),key.equals(id)));
        publishSessions();
    }
    public static void sessionSelector(Function<String, CompletableFuture<Void>> selector) { sessionSelector = selector; }
    public static Registration terminal(HostExtensions.Session session, TerminalAccess terminal) {
        String terminalKey=Objects.requireNonNullElse(terminal.terminalId(),session.id());
        terminalEntries.put(terminalKey,new TerminalEntry(session.id(),terminalKey,terminal));
        terminals.put(session.id(), terminal); sessions.put(session.id(), session); publishSessions();
        return () -> {
            terminalEntries.computeIfPresent(terminalKey,(id,entry) -> entry.access()==terminal ? null : entry);
            if(terminals.remove(session.id(),terminal)) {
                terminalEntries(session.id()).stream().findFirst().ifPresent(entry -> terminals.put(session.id(),entry.access()));
                sessions.computeIfPresent(session.id(),(id,current) -> terminals.containsKey(id) ? current : null);
                publishSessions();
            }
        };
    }
    public static Registration browser(String sessionId, FileBrowser browser) {
        browsers.put(sessionId, browser);
        return () -> browsers.remove(sessionId, browser);
    }
    public static Optional<TerminalAccess> terminal(String id) { return Optional.ofNullable(terminals.get(id)); }
    public static List<HostExtensions.Session> sessions() { return sessions.values().stream().sorted(Comparator.comparing(HostExtensions.Session::id)).toList(); }
    public static CompletableFuture<Void> selectSession(String id) {
        var selector = sessionSelector;
        return selector == null ? CompletableFuture.failedFuture(new UnsupportedOperationException("Session selection unavailable")) : selector.apply(id);
    }
    public static Registration onSessionsChanged(Consumer<List<HostExtensions.Session>> listener) {
        sessionListeners.add(listener); return () -> sessionListeners.remove(listener);
    }
    private static void publishSessions() {
        var snapshot = sessions();
        for (var listener : sessionListeners) {
            try { listener.accept(snapshot); } catch (RuntimeException error) { report(error); }
        }
    }
    static Registration fileAction(String session, HostExtensions.FileAction action) { return add(fileActions, new Owned<>(session, action)); }
    static Registration textAction(String session, HostExtensions.TextAction action) { return add(textActions, new Owned<>(session, action)); }
    static Registration preview(String session, HostExtensions.PreviewProvider provider) { return add(previews, new Owned<>(session, provider)); }
    public static List<HostExtensions.FileAction> fileActions(String session) { return matching(fileActions, session); }
    public static List<HostExtensions.TextAction> textActions(String session) { return matching(textActions, session); }
    public static List<HostExtensions.PreviewProvider> previews(String session) { return matching(previews, session); }
    private static <T> List<T> matching(List<Owned<T>> list, String session) {
        return list.stream().filter(item -> item.sessionId() == null || Objects.equals(item.sessionId(), session)).map(Owned::value).toList();
    }
    private static <T> Registration add(List<T> list, T item) {
        list.add(item); return () -> list.remove(item);
    }
    public static CompletableFuture<Void> navigate(String session, boolean remote, String directory, List<String> selection) {
        var browser = browsers.get(session);
        return browser == null ? CompletableFuture.failedFuture(new IllegalStateException("File browser is not open")) : browser.navigate(remote, directory, List.copyOf(selection));
    }
    public static CompletableFuture<Void> openPreview(HostExtensions.FileRef file, String ownerSession) {
        String id=file.sessionId() == null ? ownerSession : file.sessionId();
        var browser = id == null ? browsers.values().stream().findFirst().orElse(null) : browsers.get(id);
        return browser == null ? CompletableFuture.failedFuture(new IllegalStateException("File browser is not open")) : browser.preview(file);
    }
    public static void report(Throwable error) { org.slf4j.LoggerFactory.getLogger(HostExtensionRegistry.class).warn("Plugin host extension failed", error); }
}
