package com.jlshell.terminal.support;

import com.jlshell.plugin.api.host.TerminalAccess;
import com.jlshell.plugin.api.lifecycle.Registration;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** A bounded view of JediTerm state. Snapshot reads and writes run on the EDT. */
final class VisibleTerminalAccess implements TerminalAccess, AutoCloseable {
    private final String id;
    private final JlshellJediTermWidget widget;
    private final Supplier<String> directory;
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private CompletableFuture<Void> queue = CompletableFuture.completedFuture(null);
    private volatile boolean closed;
    private final java.util.concurrent.Semaphore batchLock=new java.util.concurrent.Semaphore(1,true);
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(512);
    private final Thread dispatcher = Thread.ofVirtual().name("terminal-events").unstarted(() -> {
        try {
            while (true) {
                Event event = events.take();
                for (var listener : listeners) {
                    try { listener.accept(event); }
                    catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(VisibleTerminalAccess.class).warn("Terminal subscriber failed",error); }
                }
                if(event.kind()==EventKind.CLOSED) break;
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { listeners.clear(); }
    });
    private static final java.util.regex.Pattern COMMAND_MARKER = java.util.regex.Pattern.compile("\\x1b\\]133;([CD])(?:;([0-9]+))?(?:\\x07|\\x1b\\\\)");
    private final StringBuilder shellMarkers = new StringBuilder();
    private boolean commandRunning;
    private volatile boolean commandTracking;
    @Override public boolean commandTrackingAvailable() { return commandTracking; }
    private synchronized void commandEvents(String text) {
        shellMarkers.append(text);
        var matcher=COMMAND_MARKER.matcher(shellMarkers);
        int consumed=0;
        while(matcher.find()) {
            consumed=matcher.end();
            if(matcher.group(1).equals("C")) { commandRunning=true; commandTracking=true; enqueue(EventKind.COMMAND_STARTED,"",null); }
            else if(commandRunning) {
                commandRunning=false;
                Integer code=null;
                try { if(matcher.group(2)!=null) code=Integer.valueOf(matcher.group(2)); } catch(NumberFormatException ignored) {}
                enqueue(EventKind.COMMAND_FINISHED,"",code);
            }
        }
        if(consumed>0) shellMarkers.delete(0,consumed);
        if(shellMarkers.length()>4096) shellMarkers.delete(0,shellMarkers.length()-4096);
    }
    private synchronized void enqueue(EventKind kind,String text,Integer exitCode) {
        long number=sequence.incrementAndGet();
        if(listeners.isEmpty()) return;
        if(text.length()>65536) text=text.substring(0,65536);
        Event event = new Event(number,Instant.now(),kind,text,exitCode);
        if (!events.offer(event)) { events.poll(); events.offer(event); }
    }
    void publish(EventKind kind, String text) {
        if (closed) return;
        enqueue(kind,text,null);
        if(kind==EventKind.OUTPUT) commandEvents(text);
    }

    VisibleTerminalAccess(String id,JlshellJediTermWidget widget,Supplier<String> directory) {
        this.id=id; this.widget=widget; this.directory=directory;
        dispatcher.start();
    }
    public String sessionId() { return id; }
    public CompletableFuture<Snapshot> snapshot(int lines,int limit) {
        if (lines < 0 || lines > 100000 || limit < 1 || limit > 4_000_000)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Snapshot exceeds bounds"));
        return SwingExecutors.supplyOnEdtAsync(() -> {
            requireOpen(); var buffer=widget.getTerminalTextBuffer(); buffer.lock();
            try {
                int count=Math.min(lines,buffer.getHistoryLinesCount());
                StringBuilder history=new StringBuilder();
                java.util.ArrayDeque<String> chunks=new java.util.ArrayDeque<>();
                int retainedCharacters=0;
                for(int row=-1;row>=-count && retainedCharacters<limit;row--) {
                    String line=buffer.getLine(row).getText()+"\n";
                    int available=limit-retainedCharacters;
                    if(line.length()>available) line=line.substring(line.length()-available);
                    chunks.addFirst(line); retainedCharacters+=line.length();
                }
                chunks.forEach(history::append);
                String screen=buffer.getScreenLines();
                boolean truncated=history.length()+screen.length()>limit || lines < buffer.getHistoryLinesCount();
                if(screen.length()>limit) screen=screen.substring(screen.length()-limit);
                int historyLimit=limit-screen.length();
                String retained=history.substring(Math.max(0,history.length()-historyLimit));
                String selection=widget.getTerminalPanel().getSelection()==null ? "" : com.jediterm.terminal.model.SelectionUtil.getSelectionText(widget.getTerminalPanel().getSelection(),buffer);
                if(selection.length()>limit) selection=selection.substring(0,limit);
                return new Snapshot(id,screen,retained,selection,directory.get(),buffer.getWidth(),buffer.getHeight(),sequence.get(),truncated);
            } finally { buffer.unlock(); }
        });
    }
    private void requireOpen() { if(closed) throw new IllegalStateException("Terminal closed"); }
    public CompletableFuture<Void> paste(String text) {
        Objects.requireNonNull(text);
        if(text.chars().anyMatch(value -> value==127 || value<32 && value!='\t' && value!='\r' && value!='\n'))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Paste accepts text. Use sendKey for terminal control keys."));
        return write(text,true);
    }
    @Override public CompletableFuture<Void> submit(String command) {
        if(command.indexOf('\n')>=0 || command.indexOf('\r')>=0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Commands must be single line"));
        return write(command+"\r",false);
    }
    private synchronized CompletableFuture<Void> write(String text,boolean paste) {
        Objects.requireNonNull(text);
        if(text.length()>1_000_000) return CompletableFuture.failedFuture(new IllegalArgumentException("Input too large"));
        queue=queue.handle((v,e)->null).thenCompose(ignored -> SwingExecutors.runOnEdtAsync(() -> {
            requireOpen(); var starter=widget.getTerminalStarter();
            if(starter==null) throw new IllegalStateException("Terminal not ready");
            String value=text;
            if(paste) {
                boolean bracketed=widget.getTerminal() instanceof com.jediterm.terminal.model.JediTerminal model
                        && model.isModelEnabled(com.jediterm.terminal.TerminalMode.BracketedPasteMode);
                if(!bracketed && (text.indexOf('\n')>=0 || text.indexOf('\r')>=0))
                    throw new IllegalStateException("Multiline paste requires bracketed paste support. Use submitBatch for execution.");
                value=text.replace("\r\n","\n").replace('\n','\r');
                if(bracketed) value="\033[200~"+value+"\033[201~";
            }
            starter.sendString(value,true);
        }));
        return queue;
    }
    public CompletableFuture<Void> sendKey(Key key) {
        return write(switch(key) {
            case ENTER -> "\r"; case INTERRUPT -> "\003"; case TAB -> "\t"; case ESCAPE -> "\033";
            case UP -> "\033[A"; case DOWN -> "\033[B"; case RIGHT -> "\033[C"; case LEFT -> "\033[D";
        },false);
    }
    public CompletableFuture<Integer> submitBatch(List<String> commands,Duration interval,Consumer<Integer> progress) {
        List<String> batch=List.copyOf(commands);
        if(batch.size()>10000 || interval.isNegative() || interval.compareTo(Duration.ofMinutes(1))>0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid batch size or interval"));
        for(String command:batch) if(command.indexOf('\n')>=0 || command.indexOf('\r')>=0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Batch commands must be single line"));
        CompletableFuture<Integer> result=new CompletableFuture<>();
        Thread worker=Thread.ofVirtual().name("terminal-batch-"+id).unstarted(() -> {
            boolean acquired=false;
            try {
                batchLock.acquire(); acquired=true;
                int delivered=0;
                for(String command:batch) {
                    if(result.isCancelled()) return;
                    submit(command).join(); delivered++;
                    if(progress!=null) progress.accept(delivered);
                    if(delivered<batch.size()) Thread.sleep(interval);
                }
                result.complete(delivered);
            } catch(Throwable error) { result.completeExceptionally(error); }
            finally { if(acquired) batchLock.release(); }
        });
        result.whenComplete((value,error)->{ if(result.isCancelled()) worker.interrupt(); });
        worker.start(); return result;
    }
    public Registration subscribe(Consumer<Event> listener) {
        requireOpen(); listeners.add(Objects.requireNonNull(listener)); return () -> listeners.remove(listener);
    }
    public synchronized void close() {
        if(closed) return;
        closed=true;
        events.clear();
        enqueue(EventKind.CLOSED,"",null);
        if(listeners.isEmpty()) dispatcher.interrupt();
    }
}
