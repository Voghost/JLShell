package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.host.*;
import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;

/** Releases contributions in reverse registration order, including resources added during disposal. */
public final class ScopedHostExtensions implements HostExtensions, AutoCloseable {
    private final String sessionId;
    private final String pluginId;
    private String pluginName;
    public void pluginName(String name) { pluginName = name; }
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private volatile boolean closed;
    private boolean viewSupported;
    public void enableViewLayout() { viewSupported=true; }
    private volatile ViewOptions viewOptions;
    private volatile Layout currentLayout;
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Layout>> layoutListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public ViewOptions viewOptions() { return viewOptions; }
    public void publishLayout(Layout value) {
        if (Objects.equals(value,currentLayout)) return;
        currentLayout=value;
        for(var listener:layoutListeners) try { listener.accept(value); } catch(RuntimeException error) { HostExtensionRegistry.report(error); }
    }

    public ScopedHostExtensions(String sessionId) { this(sessionId,"anonymous"); }
    public ScopedHostExtensions(String sessionId,String pluginId) { this.sessionId=sessionId; this.pluginId=pluginId; }
    @Override public HostInfo info() { return new HostInfo(HostExtensionRegistry.applicationVersion(),"1"); }
    public Set<Capability> capabilities() {
        var capabilities=java.util.EnumSet.of(Capability.FILE_ACTIONS,Capability.FILE_PREVIEWS,Capability.FILE_NAVIGATION,
                Capability.TASKS,Capability.DIALOGS,Capability.TERMINAL,Capability.TEXT_ACTIONS,Capability.SESSION_SELECTION);
        if(viewSupported) capabilities.add(Capability.VIEW_LAYOUT);
        boolean tracking=HostExtensionRegistry.sessions().stream()
                .filter(session -> sessionId==null || session.id().equals(sessionId))
                .anyMatch(session -> HostExtensionRegistry.terminal(session.id()).map(TerminalAccess::commandTrackingAvailable).orElse(false));
        if(tracking) capabilities.add(Capability.COMMAND_TRACKING);
        return java.util.Collections.unmodifiableSet(capabilities);
    }
    private synchronized void requireOpen() { if (closed) throw new IllegalStateException("Plugin context disposed"); }
    public synchronized <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource);
        if (closed) { release(resource); throw new IllegalStateException("Plugin context disposed"); }
        if(resources.stream().noneMatch(existing -> existing==resource)) resources.push(resource);
        return resource;
    }
    public Optional<TerminalAccess> terminal(String id) {
        requireOpen();
        if (sessionId != null && !sessionId.equals(id)) throw new IllegalArgumentException("Terminal belongs to another session");
        return HostExtensionRegistry.terminal(id).map(delegate -> new TerminalAccess() {
            public boolean commandTrackingAvailable() { return delegate.commandTrackingAvailable(); }
            public boolean commandRunning() { return delegate.commandRunning(); }
            public EventPage events(long after,int limit) { requireOpen(); return delegate.events(after,limit); }
            public String terminalId() { return delegate.terminalId(); }
            public boolean shellReady() { return delegate.shellReady(); }
            public String sessionId() { return delegate.sessionId(); }
            public CompletableFuture<Snapshot> snapshot(int lines,int limit) { requireOpen(); return timed("terminal-snapshot",() -> delegate.snapshot(lines,limit)); }
            public CompletableFuture<Void> paste(String text) { requireOpen(); return timed("terminal-input",() -> delegate.paste(text)); }
            public CompletableFuture<Void> submit(String command) { requireOpen(); return timed("terminal-command-input",() -> delegate.submit(command)); }
            public CompletableFuture<Void> sendKey(Key key) { requireOpen(); return delegate.sendKey(key); }
            public CompletableFuture<Integer> submitBatch(List<String> commands,java.time.Duration interval,Consumer<Integer> progress) {
                requireOpen(); var result=delegate.submitBatch(commands,interval,progress);
                Registration cancellation=own((Registration) () -> result.cancel(true));
                result.whenComplete((value,error) -> forget(cancellation)); return result;
            }
            public Registration subscribe(Consumer<Event> listener) { requireOpen(); return own(delegate.subscribe(event -> { if(!closed) listener.accept(event); })); }
        });
    }
    private synchronized void forget(AutoCloseable resource) { resources.remove(resource); }
    public List<Session> sessions() { requireOpen(); return HostExtensionRegistry.sessions(); }
    public CompletableFuture<Void> selectSession(String id) { requireOpen(); return HostExtensionRegistry.selectSession(id); }
    public Registration onSessionsChanged(Consumer<List<Session>> listener) { return own(HostExtensionRegistry.onSessionsChanged(listener)); }
    public Registration registerFileAction(FileAction action) { return own(HostExtensionRegistry.fileAction(sessionId, pluginId, Objects.requireNonNullElse(pluginName, pluginId), Objects.requireNonNull(action))); }
    public Registration registerTextAction(TextAction action) { return own(HostExtensionRegistry.textAction(sessionId, pluginId, Objects.requireNonNullElse(pluginName, pluginId), Objects.requireNonNull(action))); }
    public Registration registerPreview(PreviewProvider provider) {
        Objects.requireNonNull(provider);
        PreviewProvider scoped=new PreviewProvider(provider.id(),provider.title(),provider.accepts(),file ->
                provider.create().apply(file).thenCompose(this::managePreview));
        return own(HostExtensionRegistry.preview(sessionId,scoped));
    }
    private CompletableFuture<JComponent> managePreview(JComponent component) {
        CompletableFuture<JComponent> result=new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if(closed) {
                if(component instanceof AutoCloseable resource) release(resource);
                result.completeExceptionally(new IllegalStateException("Plugin context disposed"));
                return;
            }
            try { result.complete(own(new ManagedPreview(component))); }
            catch(RuntimeException error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private final class ManagedPreview extends JPanel implements AutoCloseable {
        private final JComponent content;
        private final java.util.concurrent.atomic.AtomicBoolean disposed=new java.util.concurrent.atomic.AtomicBoolean();
        ManagedPreview(JComponent content) {
            super(new java.awt.BorderLayout()); this.content=Objects.requireNonNull(content); add(content);
        }
        public void close() {
            if(!disposed.compareAndSet(false,true)) return;
            forget(this);
            Runnable release=() -> {
                java.awt.Window window=SwingUtilities.getWindowAncestor(this);
                if(window!=null) window.dispose();
                if(content instanceof AutoCloseable resource) ScopedHostExtensions.release(resource);
                removeAll();
            };
            if(SwingUtilities.isEventDispatchThread()) release.run(); else SwingUtilities.invokeLater(release);
        }
    }
    public CompletableFuture<Void> openPreview(FileRef file) { requireOpen(); return HostExtensionRegistry.openPreview(file,sessionId); }
    public CompletableFuture<Void> navigate(String id, boolean remote, String dir, List<String> selection) {
        requireOpen(); return HostExtensionRegistry.navigate(id, remote, dir, selection);
    }
    public void viewOptions(ViewOptions options) {
        requireOpen(); Objects.requireNonNull(options);
        if(!viewSupported) throw new UnsupportedOperationException("This context has no plugin view");
        viewOptions=options;
    }
    public Optional<Layout> layout() { return Optional.ofNullable(currentLayout); }
    public Registration onLayoutChanged(Consumer<Layout> listener) { layoutListeners.add(listener); return own((Registration) () -> layoutListeners.remove(listener)); }
    public <T> CompletableFuture<T> task(String title, Function<TaskContext,T> work) {
        requireOpen();
        CompletableFuture<T> result = new CompletableFuture<>();
        var latestProgress = new java.util.concurrent.atomic.AtomicReference<TaskProgress>(new TaskProgress(0,title));
        showTask(title,result,latestProgress);
        String taskId=java.util.UUID.randomUUID().toString();
        Runnable cancel=() -> result.cancel(true);
        HostExtensionRegistry.task(new HostExtensionRegistry.Task(taskId,pluginId,title,0,title,cancel));
        result.whenComplete((value,error) -> HostExtensionRegistry.finishTask(taskId));
        var timing=com.jlshell.core.support.PerformanceTrace.start("plugin-task",pluginId);
        result.whenComplete((value,error) -> { if(error==null) timing.finish("success"); else timing.failed(error); });
        Thread worker = Thread.ofVirtual().name("plugin-task-" + title).unstarted(() -> {
            try { result.complete(work.apply(new TaskContext() {
                public boolean cancelled() { return result.isCancelled() || Thread.currentThread().isInterrupted(); }
                public void progress(double fraction, String message) {
                    if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalArgumentException("Progress must be 0..1");
                    latestProgress.set(new TaskProgress(fraction,message));
                    if(!result.isDone()) HostExtensionRegistry.progressTask(new HostExtensionRegistry.Task(taskId,pluginId,title,fraction,message,cancel));
                }
            })); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        Registration cancellation=own((Registration) () -> { result.cancel(true); worker.interrupt(); });
        result.whenComplete((value,error) -> forget(cancellation));
        result.whenComplete((value,error) -> { if (result.isCancelled()) worker.interrupt(); });
        worker.start(); return result;
    }
    private <T> CompletableFuture<T> timed(String operation,Supplier<CompletableFuture<T>> action) {
        var trace=com.jlshell.core.support.PerformanceTrace.start(operation,pluginId);
        try {
            return action.get().whenComplete((value,error) -> { if(error==null) trace.finish("success"); else trace.failed(error); });
        } catch(RuntimeException error) { trace.failed(error); return CompletableFuture.failedFuture(error); }
    }
    private record TaskProgress(double fraction,String message) {}
    private void showTask(String title,CompletableFuture<?> result,java.util.concurrent.atomic.AtomicReference<TaskProgress> progress) {
        if(java.awt.GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            javax.swing.Timer delay = new javax.swing.Timer(250,null);
            delay.setRepeats(false);
            delay.addActionListener(event -> {
                if(result.isDone()) return;
                JDialog dialog=new JDialog(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),title);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                JPanel body=new JPanel(new java.awt.BorderLayout(12,12));
                body.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
                JLabel message=new JLabel(title); JProgressBar bar=new JProgressBar(0,1000);
                JButton cancel=new JButton("取消"); cancel.addActionListener(e -> result.cancel(true));
                body.add(message,java.awt.BorderLayout.NORTH); body.add(bar); body.add(cancel,java.awt.BorderLayout.SOUTH);
                dialog.add(body); dialog.setSize(440,170); dialog.setLocationRelativeTo(dialog.getOwner());
                javax.swing.Timer refresh=new javax.swing.Timer(100,e -> {
                    TaskProgress value=progress.get(); message.setText(value.message()); bar.setValue((int)(value.fraction()*1000));
                    if(result.isDone()) {
                        ((javax.swing.Timer)e.getSource()).stop(); cancel.setEnabled(false);
                        message.setText(result.isCancelled()?"已取消":result.isCompletedExceptionally()?"任务失败":"已完成");
                        if(!result.isCompletedExceptionally()) bar.setValue(1000);
                    }
                });
                Registration resource=own((Registration) () -> SwingUtilities.invokeLater(() -> { refresh.stop(); dialog.dispose(); }));
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    public void windowClosed(java.awt.event.WindowEvent e) { refresh.stop(); result.cancel(true); forget(resource); }
                });
                refresh.start(); dialog.setVisible(true);
            });
            result.whenComplete((value,error) -> SwingUtilities.invokeLater(delay::stop));
            delay.start();
        });
    }

    private <T> CompletableFuture<T> onEdt(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> { try { requireOpen(); result.complete(operation.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result;
    }
    public CompletableFuture<Boolean> confirm(String title,String message) {
        return onEdt(() -> JOptionPane.showConfirmDialog(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),message,title,JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION);
    }
    public CompletableFuture<Optional<String>> prompt(String title,String message,String initial) {
        return onEdt(() -> Optional.ofNullable((String) JOptionPane.showInputDialog(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),message,title,JOptionPane.QUESTION_MESSAGE,null,null,initial)));
    }
    public CompletableFuture<Optional<String>> chooseFile(String title,boolean save) {
        return onEdt(() -> { JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle(title);
            int result = save ? chooser.showSaveDialog(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()) : chooser.showOpenDialog(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow());
            return result == JFileChooser.APPROVE_OPTION ? Optional.of(chooser.getSelectedFile().getAbsolutePath()) : Optional.empty(); });
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        while (!resources.isEmpty()) release(resources.pop());
    }
    private static void release(AutoCloseable resource) {
        try { resource.close(); } catch (Exception error) { HostExtensionRegistry.report(error); }
    }
}
