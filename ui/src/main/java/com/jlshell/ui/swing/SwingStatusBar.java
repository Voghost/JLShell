package com.jlshell.ui.swing;

import com.jlshell.core.support.RecentApplicationLogs;
import javax.swing.*;
import com.jlshell.ui.swing.idea.IdeaContentPopup;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Function;

/** Compact status line. Details are lightweight popups above it, never extra layout rows. EDT only. */
final class SwingStatusBar extends JPanel implements AutoCloseable {
    record SessionInfo(String name, String address, boolean local, String state, Instant connectedAt, String terminal, String encoding) {}
    private final Function<String,String> text;
    private final RecentApplicationLogs logs;
    private final JButton latest = compact(""), session = compact(""), transfers = compact(""), encoding = compact("UTF-8");
    private Supplier<SessionInfo> sessionSource = () -> null;
    private Supplier<List<String>> transferSource = List::of;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AutoCloseable subscription;
    private final Timer timer = new Timer(1000, event -> refresh());
    private List<RecentApplicationLogs.Entry> entries = List.of();
    private List<String> activeTransfers = List.of();
    private SessionInfo info;
    private IdeaContentPopup popup;
    private JList<RecentApplicationLogs.Entry> activityList;
    private JList<String> transferList;
    private boolean closed;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    SwingStatusBar(Function<String,String> text, RecentApplicationLogs logs) {
        super(new BorderLayout(4,0)); this.text = text; this.logs = logs;
        setName("workbench.statusBar"); setBorder(BorderFactory.createEmptyBorder(3,7,3,7)); setOpaque(false);
        latest.setName("status.logs"); latest.setHorizontalAlignment(SwingConstants.LEFT);
        latest.setMinimumSize(new Dimension(0,0)); latest.addActionListener(e -> showLogs()); add(latest);
        JPanel right = new JPanel(); right.setOpaque(false); right.setLayout(new BoxLayout(right,BoxLayout.X_AXIS));
        session.setName("status.session"); transfers.setName("status.transfers"); encoding.setName("status.encoding");
        right.add(session); right.add(transfers); right.add(encoding); add(right,BorderLayout.EAST);
        session.addActionListener(e -> showSession()); encoding.addActionListener(e -> showSession());
        transfers.addActionListener(e -> showTransfers());
        subscription = logs.subscribe(() -> {
            if (queued.compareAndSet(false,true)) SwingUtilities.invokeLater(() -> {
                queued.set(false); if (!closed) refresh();
            });
        });
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing() && !closed) timer.start(); else { timer.stop(); hidePopup(); }
            }
        });
        refresh();
    }
    // Same geometry as the former JLabel with (3,12,3,12) insets, at every UI font size.
    @Override public Dimension getPreferredSize() { return new Dimension(0,getFontMetrics(UIManager.getFont("Label.font")).getHeight()+6); }
    @Override public void addNotify() { super.addNotify(); if (!closed) timer.start(); }
    @Override public void removeNotify() { timer.stop(); hidePopup(); super.removeNotify(); }
    void sources(Supplier<SessionInfo> session, Supplier<List<String>> transfers) {
        sessionSource=session; transferSource=transfers; refresh();
    }
    void status(String message) {
        if (message != null && !message.isBlank()) {
            var current = logs.snapshot();
            if (!current.isEmpty() && current.getFirst().message().equals(message)) return;
            logs.append(Instant.now(),"INFO","Workbench",message);
        }
    }
    void refresh() {
        var previous = entries;
        var selected = activityList == null ? null : activityList.getSelectedValue();
        entries = logs.snapshot(); info = sessionSource.get(); activeTransfers = transferSource.get();
        if (entries.isEmpty()) latest.setText(text.apply("statusbar.noLogs"));
        else {
            var last = entries.getFirst();
            latest.setText(TIME.format(last.time()) + "  " + last.message().lines().findFirst().orElse(""));
        }
        latest.setToolTipText(text.apply("statusbar.recentLogs"));
        session.setIcon(info == null ? null : new Icon() {
            public int getIconWidth(){return 8;} public int getIconHeight(){return 8;}
            public void paintIcon(Component c,Graphics g,int x,int y){
                Graphics2D copy=(Graphics2D)g.create();
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Color color=UIManager.getColor(info.state().equals("connected")?"Actions.Green":"Label.disabledForeground");
                copy.setColor(color==null?c.getForeground():color);copy.fillOval(x+1,y+1,6,6);copy.dispose();
            }
        });
        session.setText(info==null ? text.apply("workspace.noOpenSessions") : (info.local()?text.apply("statusbar.local"):"SSH")+" · "+text.apply("statusbar."+info.state())
                +(info.connectedAt()!=null && info.state().equals("connected") ? "  "+elapsed(info.connectedAt()):""));
        session.setToolTipText(info==null?null:info.name()+" · "+info.address());
        session.setEnabled(info!=null); encoding.setText(info == null ? "" : info.encoding());
        encoding.setVisible(info!=null && !info.encoding().isBlank() && getWidth()>760);
        transfers.setText("⇅ "+text.apply("statusbar.transfers")+" "+activeTransfers.size()); transfers.setVisible(!activeTransfers.isEmpty());
        if (activityList != null && popup != null && popup.isShowing() && !entries.equals(previous)) {
            activityList.setListData(entries.toArray(RecentApplicationLogs.Entry[]::new));
            if (selected != null) activityList.setSelectedValue(selected, false);
        }
        if (transferList != null) transferList.setListData(activeTransfers.toArray(String[]::new));
    }
    private static String elapsed(Instant start) {
        long seconds=Math.max(0,Duration.between(start,Instant.now()).toSeconds());
        return seconds<3600?String.format("%02d:%02d",seconds/60,seconds%60):String.format("%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
    }
    private static JButton compact(String value) {
        JButton button=new JButton(value); button.setMargin(new Insets(0,0,0,0));
        button.setBorder(BorderFactory.createEmptyBorder(0,5,0,5)); button.setContentAreaFilled(false);
        button.putClientProperty("JButton.buttonType","toolBarButton"); button.putClientProperty("html.disable",true);
        button.setFont(UIManager.getFont("Label.font")); return button;
    }
    private JPanel heading(String title) {
        JPanel header=new JPanel(new BorderLayout()); header.add(new JLabel(title));
        JButton close=compact("×"); close.setToolTipText(text.apply("tab.close")); close.addActionListener(e->hidePopup());
        header.add(close,BorderLayout.EAST); return header;
    }
    private void showLogs() {
        hidePopup();
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.add(heading(text.apply("statusbar.recentLogs")), BorderLayout.NORTH);
        if (entries.isEmpty()) {
            JLabel empty = new JLabel(text.apply("statusbar.noLogs"), SwingConstants.CENTER);
            empty.setForeground(SwingTheme.color("secondary"));
            empty.setBorder(BorderFactory.createEmptyBorder(32, 0, 32, 0)); body.add(empty);
        } else {
            activityList = new JList<>(entries.toArray(RecentApplicationLogs.Entry[]::new));
            activityList.setName("status.activities"); activityList.setBackground(SwingTheme.color("popup"));
            activityList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            activityList.setCellRenderer((list, entry, index, selected, focused) -> {
                JPanel row = new JPanel(new BorderLayout(12, 5)); row.setOpaque(false);
                row.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
                JLabel time = new JLabel(TIME.format(entry.time())); time.setForeground(SwingTheme.color("secondary"));
                boolean problem = entry.level().equals("ERROR") || entry.level().equals("WARN");
                JLabel state = new JLabel(text.apply(problem ? "statusbar.needsAttention" : "statusbar.activity"));
                state.setForeground(problem ? UIManager.getColor("Component.warning.focusedBorderColor") : SwingTheme.color("secondary"));
                JPanel meta = new JPanel(new BorderLayout()); meta.setOpaque(false); meta.add(state); meta.add(time, BorderLayout.EAST);
                row.add(meta, BorderLayout.NORTH);
                JTextArea message = new JTextArea(entry.message()); message.setEditable(false); message.setOpaque(false);
                message.setLineWrap(true); message.setWrapStyleWord(true); message.setFont(UIManager.getFont("Label.font"));
                int width = Math.max(200, list.getWidth() > 0 ? list.getWidth() : 440);
                message.setSize(width, Short.MAX_VALUE); row.add(message);
                return row;
            });
            JScrollPane scroll = new JScrollPane(activityList); scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getViewport().setBackground(SwingTheme.color("popup"));
            scroll.setPreferredSize(new Dimension(460, Math.min(290, entries.size() * 76)));
            body.add(scroll);
        }
        showPopup(body, latest, 500);
    }
    private void showSession() {
        if(info==null)return;
        hidePopup();
        JPanel body=new JPanel(new BorderLayout(0,12));body.add(heading(text.apply("statusbar.session")),BorderLayout.NORTH);
        JPanel values=new JPanel(new GridLayout(0,1,0,6));
        values.add(new JLabel(info.name())); values.add(new JLabel(info.address()));
        values.add(new JLabel(text.apply("statusbar."+info.state())+(info.connectedAt()==null?"":" · "+elapsed(info.connectedAt()))));
        values.add(new JLabel(info.terminal()));body.add(values); showPopup(body,session,320);
    }
    private void showTransfers() {
        hidePopup();
        JPanel body=new JPanel(new BorderLayout(0,8));body.add(heading(text.apply("statusbar.transfers")),BorderLayout.NORTH);
        transferList=new JList<>(activeTransfers.toArray(String[]::new));body.add(new JScrollPane(transferList));
        body.setPreferredSize(new Dimension(480,180));showPopup(body,transfers,420);
    }
    private void showPopup(JPanel body, JComponent anchor, int width) {
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        body.setBackground(SwingTheme.color("popup"));
        for (Component child : body.getComponents()) {
            if (child instanceof JPanel panel) { panel.setOpaque(false); }
        }
        popup = new IdeaContentPopup(); popup.showAbove(anchor, body, width);
    }
    void applyTheme() { hidePopup(); latest.setForeground(SwingTheme.color("secondary")); refresh(); }
    private void hidePopup(){if(popup!=null)popup.close();popup=null;activityList=null;transferList=null;}
    @Override public void close(){closed=true;timer.stop();hidePopup();try{subscription.close();}catch(Exception ignored){}}
}
