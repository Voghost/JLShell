package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.KeyStroke;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.jediterm.core.TerminalCoordinates;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalActionProvider;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;

/**
 * 暴露受保护的字体/布局刷新能力。
 * 过滤 macOS SwingNode 下修饰键单独触发的 keyChar=\0 噪音事件。
 * 覆盖右键菜单，使其与应用主题颜色一致并支持多语言。
 */
public class RefreshableTerminalPanel extends TerminalPanel {

    // JediTerm 硬编码的英文菜单项名 → i18n key 映射
    private static final Map<String, String> ACTION_KEY_MAP = Map.ofEntries(
            Map.entry("Copy",         "terminal.action.copy"),
            Map.entry("Paste",        "terminal.action.paste"),
            Map.entry("Clear Buffer", "terminal.action.clearBuffer"),
            Map.entry("Select All",   "terminal.action.selectAll"),
            Map.entry("Find",         "terminal.action.find"),
            Map.entry("Page Up",      "terminal.action.pageUp"),
            Map.entry("Page Down",    "terminal.action.pageDown"),
            Map.entry("Line Up",      "terminal.action.lineUp"),
            Map.entry("Line Down",    "terminal.action.lineDown")
    );

    private final JlshellSettingsProvider jlshellSettings;
    private final Function<String, String> i18n;
    private boolean ignoreNextShiftInsertTyped;
    private final TerminalGlyphGraphics.LayoutCache glyphLayouts = new TerminalGlyphGraphics.LayoutCache();
    private final TerminalViewportCache viewportCache = new TerminalViewportCache();
    private final boolean viewportBlitEnabled = !Boolean.getBoolean("jlshell.terminal.disableScrollBlit");
    private final java.util.concurrent.atomic.AtomicLong paintRevision = new java.util.concurrent.atomic.AtomicLong();
    private long lastScrollInput;
    private final TerminalScrollAnimation scrolling = new TerminalScrollAnimation();
    private final javax.swing.Timer scrollTimer = new javax.swing.Timer(7, event -> advanceScroll());
    private final java.util.function.LongSupplier scrollClock;
    private final TerminalWheelInput wheelInput;
    private com.jediterm.terminal.model.TerminalLine scrollAnchor;
    private int scrollExtent;
    private int scrollOrigin;
    private int scrollMinimum, scrollMaximum;
    private final java.util.concurrent.atomic.AtomicLong outputScrollRows = new java.util.concurrent.atomic.AtomicLong();
    private long scrollCoordinateRows;
    private final TerminalScrollDiagnostics scrollDiagnostics = Boolean.getBoolean("jlshell.terminal.traceScroll")
            ? new TerminalScrollDiagnostics() : null;
    private boolean updatingScrollModel;
    private boolean paintingScrollEdge;
    private TextStyle drawingStyle;
    private final Runnable highlightChanged = () -> SwingUtilities.invokeLater(this::repaint);
    private com.jlshell.terminal.model.TerminalHighlightConfig highlightConfig;
    private TerminalLogHighlighter logHighlighter;
    private Font lastVisualFont;
    private float lastVisualLineSpacing;
    public record PopupAction(String label, String shortcut, boolean enabled, boolean separator, Runnable action) {}
    @FunctionalInterface public interface PopupPresenter {
        void show(java.awt.Component invoker, int x, int y, java.util.List<PopupAction> actions);
    }
    private PopupPresenter popupPresenter;
    public void setPopupPresenter(PopupPresenter presenter) { popupPresenter = presenter; }

    /**
     * JediTermWidget 在 init 时通过 setCoordAccessor 注入 TerminalCoordinates，
     * 这里捕获引用用于 IME 候选窗定位（见 getCursorLocationInComponent）。
     */
    private volatile TerminalCoordinates capturedCoordAccessor;

    public RefreshableTerminalPanel(
            SettingsProvider settingsProvider,
            TerminalTextBuffer terminalTextBuffer,
            StyleState styleState,
            JlshellSettingsProvider jlshellSettings,
            Function<String, String> i18n
    ) {
        this(settingsProvider, terminalTextBuffer, styleState, jlshellSettings, i18n,
                new TerminalWheelInput(System.getProperty("os.name", "").startsWith("Mac")));
    }

    RefreshableTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer terminalTextBuffer,
            StyleState styleState, JlshellSettingsProvider jlshellSettings, Function<String, String> i18n,
            TerminalWheelInput wheelInput) {
        this(settingsProvider, terminalTextBuffer, styleState, jlshellSettings, i18n, wheelInput, System::nanoTime);
    }

    RefreshableTerminalPanel(SettingsProvider settingsProvider, TerminalTextBuffer terminalTextBuffer,
            StyleState styleState, JlshellSettingsProvider jlshellSettings, Function<String, String> i18n,
            TerminalWheelInput wheelInput, java.util.function.LongSupplier scrollClock) {
        super(settingsProvider, terminalTextBuffer, styleState);
        this.jlshellSettings = jlshellSettings;
        this.i18n = i18n;
        this.wheelInput = wheelInput;
        this.scrollClock = scrollClock;
        scrollTimer.setCoalesce(true);
        getVerticalScrollModel().addChangeListener(event -> scrollModelChanged());
        if (jlshellSettings != null && jlshellSettings.highlights() != null)
            jlshellSettings.highlights().listen(highlightChanged);
    }

    @Override
    protected void handleMouseWheelEvent(@NotNull java.awt.event.MouseWheelEvent event, @NotNull javax.swing.JScrollBar scrollBar) {
        if (event.isShiftDown() || event.isControlDown() || event.isAltDown() || event.isMetaDown()
                || getTerminalTextBuffer().isUsingAlternateBuffer() || !isLocalMouseAction(event)) {
            stopScrolling(); super.handleMouseWheelEvent(event, scrollBar); return;
        }
        // JetBrains TouchScrollUtil uses phases 2/3/4 for begin/update/end and pixel deltas.
        int phase=event.getScrollType();
        if (phase == 2) { stopScrollTimer(); scrolling.freeze(); event.consume(); return; }
        if (phase == 4) { event.consume(); return; } // Native touch has no application-generated tail to finish.
        var model = getVerticalScrollModel();
        int min = -getTerminalTextBuffer().getHistoryLinesCount();
        long now = scrollClock.getAsLong();
        lastScrollInput = System.nanoTime();
        var motion = wheelInput.motion(event, myCharSize.height, getTerminalTextBuffer().getHeight(), now);
        if (motion.rows() == 0) return;
        if (scrollDiagnostics != null) scrollDiagnostics.input(now, motion.rows() * myCharSize.height, event.getWhen());
        if (!scrolling.active()) scrollCoordinateRows = outputScrollRows.get();
        if (motion.direct()) scrolling.directWheel(motion.rows(), model.getValue(), min, 0, now);
        else scrolling.wheel(motion.rows(), motion.precise(), model.getValue(), min, 0, now);
        applyScrollPosition();
        if (scrolling.animating()) startScrollTimer(); else stopScrollTimer();
        event.consume();
    }

    boolean scrollBarTo(int target) {
        if (getTerminalTextBuffer().isUsingAlternateBuffer()) return false;
        var model = getVerticalScrollModel();
        lastScrollInput = System.nanoTime();
        if (!scrolling.active()) scrollCoordinateRows = outputScrollRows.get();
        scrolling.dragTo(target, model.getValue(), model.getMinimum(), 0, scrollClock.getAsLong());
        applyScrollPosition();
        if (scrolling.animating()) startScrollTimer();
        return true;
    }

    private void startScrollTimer() {
        if(!scrollTimer.isRunning())AnimationTimerPrecision.shared().request(this);
        scrollTimer.start();
    }
    private void stopScrollTimer() {
        scrollTimer.stop();AnimationTimerPrecision.shared().cancel(this);
    }

    private void advanceScroll() {
        if (!isShowing() || getTerminalTextBuffer().isUsingAlternateBuffer()) { stopScrolling(); return; }
        scrolling.advance(scrollClock.getAsLong()); applyScrollPosition();
        if (!scrolling.animating()) stopScrollTimer();
    }

    private void applyScrollPosition() {
        applyScrollPosition(true);
    }

    private void applyScrollPosition(boolean requestFrame) {
        var model = getVerticalScrollModel();
        var buffer = getTerminalTextBuffer();
        buffer.lock();
        try {
            int value = Math.max(model.getMinimum(), Math.min(0, renderedScrollOrigin()));
            updatingScrollModel = true;
            try { model.setValue(value); }
            finally { updatingScrollModel = false; }
            // Output can arrive between two wheel events, before JediTerm drains its scrollDy.
            // Capture the line in the pending output's coordinates, not the stale scrollbar grid.
            long anchorRow = value + outputScrollRows.get() - scrollCoordinateRows;
            scrollAnchor = value < 0 && anchorRow < 0 && anchorRow >= -buffer.getHistoryLinesCount()
                    ? buffer.getLine((int)anchorRow) : null;
            scrollExtent = model.getExtent();
            scrollOrigin = model.getValue();
            scrollMinimum = model.getMinimum(); scrollMaximum = model.getMaximum();
        } finally { buffer.unlock(); }
        if (requestFrame) javax.swing.RepaintManager.currentManager(this).addDirtyRegion(this, 0, 0, getWidth(), getHeight());
    }

    private void scrollModelChanged() {
        if (updatingScrollModel || !scrolling.active()) return;
        var model = getVerticalScrollModel();
        var buffer = getTerminalTextBuffer();
        buffer.lock();
        try {
            int value = model.getValue();
            // Press/release toggles valueIsAdjusting without moving the viewport. In particular,
            // releasing the thumb must not discard the final interpolation segment.
            if (value == scrollOrigin && model.getExtent() == scrollExtent
                    && model.getMinimum() == scrollMinimum && model.getMaximum() == scrollMaximum) return;
            // JediTerm compensates history coordinates when output arrives, including when its
            // history ring is full. Retain the fraction only if the same line is still at the top.
            // A scrollbar/search/key jump, resize, clear or discarded anchor cancels the old offset.
            if (model.getExtent() == scrollExtent && scrollAnchor != null
                    && value < 0 && value >= -buffer.getHistoryLinesCount()
                    && buffer.getLine(value) == scrollAnchor) {
                scrolling.shift(value - scrollOrigin, model.getMinimum(), 0);
                scrollOrigin = value;
                scrollMinimum = model.getMinimum(); scrollMaximum = model.getMaximum();
                scrollCoordinateRows = outputScrollRows.get();
                if (scrollDiagnostics != null) scrollDiagnostics.historyAdjusted();
            } else {
                if (scrollDiagnostics != null) scrollDiagnostics.externalReset();
                stopScrolling();
            }
        } finally { buffer.unlock(); }
    }

    private void stopScrolling() {
        stopScrollTimer(); scrolling.cancel(getVerticalScrollModel().getValue()); scrollAnchor = null;
    }

    @Override public void scrollArea(int top, int size, int dy) {
        // Called by JediTerm's output thread while it holds the text buffer lock.
        outputScrollRows.addAndGet(dy);
        super.scrollArea(top, size, dy);
    }

    @Override public void dispose() {
        stopScrolling();
        viewportCache.clear();
        if (scrollDiagnostics != null) scrollDiagnostics.close();
        glyphLayouts.clear(); super.dispose();
    }
    @Override public void terminalMouseModeSet(@NotNull com.jediterm.terminal.emulator.mouse.MouseMode mode) {
        super.terminalMouseModeSet(mode);
        SwingUtilities.invokeLater(this::stopScrolling);
    }
    @Override protected void processMouseEvent(java.awt.event.MouseEvent event) {
        if (event.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED) { stopScrollTimer(); scrolling.freeze(); }
        int pixels = event.getButton() == java.awt.event.MouseEvent.BUTTON1 ? scrollingPixels() : 0;
        event.translatePoint(0, pixels);
        try { super.processMouseEvent(event); } finally { event.translatePoint(0, -pixels); }
    }
    @Override protected void processMouseMotionEvent(java.awt.event.MouseEvent event) {
        // Match hit testing to the fractional viewport, without snapping the text under the pointer.
        int pixels = scrollingPixels(); event.translatePoint(0, pixels);
        try { super.processMouseMotionEvent(event); } finally { event.translatePoint(0, -pixels); }
    }

    private int scrollingPixels() {
        if (!scrolling.active() || myCharSize.height <= 0 || getTerminalTextBuffer().isUsingAlternateBuffer()) return 0;
        return (int)Math.floorMod(Math.round(scrolling.position() * myCharSize.height), myCharSize.height);
    }

    private int renderedScrollOrigin() {
        return (int)Math.floorDiv(Math.round(scrolling.position() * Math.max(1, myCharSize.height)),
                Math.max(1, myCharSize.height));
    }

    @Override
    public void paintComponent(Graphics graphics) {
        long started = scrollDiagnostics == null ? 0 : System.nanoTime();
        try { paintViewport(graphics); }
        finally {
            if (scrollDiagnostics != null) scrollDiagnostics.painted(started, System.nanoTime(), getWidth(), getHeight(),
                    ((Graphics2D)graphics).getTransform().getScaleX(), viewportCache);
        }
    }

    private void paintViewport(Graphics graphics) {
        if (scrolling.animating()) {
            // Sample at paint time as well as timer time. A queued repaint must not display a
            // stale timer position when the EDT had other work to finish before this frame.
            scrolling.advance(scrollClock.getAsLong());
            applyScrollPosition(false);
            if (!scrolling.animating()) stopScrollTimer();
        }
        if (viewportBlitEnabled
                && System.nanoTime() - lastScrollInput < 250_000_000L && scrolling.active()
                && getVerticalScrollModel().getValue() < 0
                && !getTerminalTextBuffer().isUsingAlternateBuffer() && getSelection() == null && getFindResult() == null) {
            var buffer = getTerminalTextBuffer();
            buffer.lock();
            try {
                // Pending output must first be drained by JediTerm's original renderer.
                // Otherwise a blit could mix old and new history coordinates.
                long revision = paintRevision.get();
                if (scrollCoordinateRows == outputScrollRows.get()
                        && viewportCache.paint((Graphics2D)graphics, getWidth(), getHeight(),
                        getVerticalScrollModel().getValue() * myCharSize.height + scrollingPixels(),
                        revision, myCharSize.height * 2, this::paintGridViewport)) return;
            } finally { buffer.unlock(); }
        }
        viewportCache.clear();
        paintGridViewport(graphics);
    }

    private void paintGridViewport(Graphics graphics) {
        int pixels = scrollingPixels();
        int gridHeight = getTerminalTextBuffer().getHeight() * myCharSize.height;
        if (pixels == 0 && (getHeight() <= gridHeight || getVerticalScrollModel().getValue() >= 0)) {
            paintTerminal(graphics); return;
        }
        // Paint two clipped grid views instead of allocating full-viewport animation images.
        // The second pass supplies the exposed bottom strip, so no blank line flashes between rows.
        int origin = getVerticalScrollModel().getValue();
        Graphics2D first = (Graphics2D) graphics.create();
        getTerminalTextBuffer().lock();
        try {
            first.translate(0, -pixels); paintTerminal(first);
            // The first pass drains JediTerm's pending history compensation. Restore that new
            // origin after the edge pass, never the stale origin captured before painting.
            origin = getVerticalScrollModel().getValue();
            if (scrollingPixels() != pixels) {
                // A resize or discarded history anchor can cancel the fractional viewport in
                // updateScrolling. Repaint the settled viewport in this frame, not one frame later.
                paintTerminal(graphics);
                return;
            }
            // Component heights are rarely an exact multiple of a terminal row. Include the
            // partial row below the grid as well, otherwise its blank band jumps at row boundaries.
            int edgeStart = Math.min(getHeight(), gridHeight - pixels);
            int edgeHeight = getHeight() - edgeStart;
            var dirty = graphics.getClipBounds();
            if (dirty != null && !dirty.intersects(0, edgeStart, getWidth(), edgeHeight)) return;
            updatingScrollModel = true; paintingScrollEdge = true;
            getVerticalScrollModel().setValue(Math.min(0, origin + 1));
            Graphics2D edge = (Graphics2D) graphics.create();
            try {
                int translation = myCharSize.height - pixels;
                edge.translate(0, translation);
                java.awt.Rectangle clip = new java.awt.Rectangle(0, edgeStart - translation, getWidth(), edgeHeight);
                var existing = edge.getClip();
                java.awt.geom.Area limit = new java.awt.geom.Area(clip);
                if (existing != null) limit.intersect(new java.awt.geom.Area(existing));
                paintTerminal(new HighlightGraphics(edge, (x, y, original) -> null, limit));
            } finally { edge.dispose(); }
        } finally {
            getVerticalScrollModel().setValue(origin); updatingScrollModel = false; paintingScrollEdge = false;
            getTerminalTextBuffer().unlock(); first.dispose();
        }
    }

    private void paintTerminal(Graphics graphics) {
        var glyphs = new TerminalGlyphGraphics((Graphics2D)graphics.create(),glyphLayouts);
        try { paintTerminalContent(glyphs); } finally { glyphs.dispose(); }
    }

    private void paintTerminalContent(Graphics graphics) {
        var config = jlshellSettings == null || jlshellSettings.highlights() == null
                ? com.jlshell.terminal.model.TerminalHighlightConfig.defaults()
                : jlshellSettings.highlights().config();
        if (!config.equals(highlightConfig)) {
            highlightConfig = config;
            logHighlighter = new TerminalLogHighlighter(config);
        }
        if (!config.enabled() || getTerminalTextBuffer().isUsingAlternateBuffer() || myCharSize.height <= 0 || myCharSize.width <= 0) {
            super.paintComponent(graphics);
            return;
        }
        java.util.Map<Integer, java.util.Map<Integer, Color>> visible = new java.util.HashMap<>();
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            super.paintComponent(new HighlightGraphics(copy, (x, baseline, original) -> {
                // Preserve explicit ANSI colors, hyperlinks, selection, search matches and inverse cursor.
                TextStyle style = drawingStyle;
                if (style == null || style instanceof com.jediterm.terminal.HyperlinkStyle
                        || style.hasOption(TextStyle.Option.INVERSE) || style.hasOption(TextStyle.Option.DIM)
                        || !java.util.Objects.equals(original, jlshellSettings.foregroundColor())) return null;
                int row = Math.floorDiv(baseline - 1, myCharSize.height) + myClientScrollOrigin;
                int column = Math.floorDiv(x - getInsetX(), myCharSize.width);
                if (getSelection() != null && getSelection().intersect(column, row, 1) != null) return null;
                if (style.getBackground() != null
                        && !style.getBackground().equals(jlshellSettings.getDefaultBackground())) return null;
                return visible.computeIfAbsent(row, this::highlightRow).get(column);
            }));
        } finally { copy.dispose(); }
    }

    private java.util.Map<Integer, Color> highlightRow(int row) {
        String cells = getTerminalTextBuffer().getLine(row).getText();
        StringBuilder text = new StringBuilder(cells.length());
        int[] columns = new int[cells.length()];
        for (int i = 0; i < cells.length(); i++) {
            if (cells.charAt(i) != CharUtils.DWC) {
                columns[text.length()] = i;
                text.append(cells.charAt(i));
            }
        }
        java.util.Map<Integer, Color> result = new java.util.HashMap<>();
        for (var span : logHighlighter.match(text.toString())) {
            for (int i = span.start(); i < span.end(); i++) result.put(columns[i], span.color());
        }
        return result;
    }

    @Override
    public void repaint() {
        // Temporary scrollbar positions used by the clipped edge pass are not real user scrolls.
        // Scheduling another repaint here would create a perpetual redraw loop at fractional offsets.
        if (!paintingScrollEdge) {
            if (!updatingScrollModel && paintRevision != null) paintRevision.incrementAndGet();
            super.repaint();
        }
    }

    @Override public void removeNotify() {
        viewportCache.clear();
        super.removeNotify();
    }

    @Override
    public java.awt.im.InputMethodRequests getInputMethodRequests() {
        return new TerminalInputMethodRequests(super.getInputMethodRequests());
    }

    public void refreshVisuals() {
        viewportCache.clear();
        Font font = jlshellSettings.getTerminalFont();
        float spacing = jlshellSettings.getLineSpacing();
        if (!font.equals(lastVisualFont) || Float.compare(spacing, lastVisualLineSpacing) != 0) {
            reinitFontAndResize();
            lastVisualFont = font;
            lastVisualLineSpacing = spacing;
        }
        repaint();
    }

    /**
     * 覆写 JediTerm 默认的抗锯齿设置，提升文字渲染清晰度。
     *
     * JediTerm 默认只设 KEY_TEXT_ANTIALIASING = ON（灰度 AA），
     * 在 SwingNode 嵌入场景下文字会显得"隔了一层纱"。
     *
     * 改进：
     * - Windows: 使用 LCD 子像素抗锯齿（HGRB），显著提升文字锐度
     * - macOS/Linux: 灰度 AA（macOS 的 SwingNode 不支持 LCD AA）
     * - 启用 KEY_RENDERING = QUALITY，避免几何缩放模糊
     * - 启用 KEY_FRACTIONALMETRICS = ON，让字符定位更精确
     */
    @Override
    protected void setupAntialiasing(Graphics graphics) {
        if (graphics instanceof Graphics2D gfx) {
            Object aaMode;
            Object fractionalMetrics;
            if (isWindows()) {
                // Windows SwingNode 支持 LCD 子像素渲染；关闭 fractional metrics
                // 让等宽终端字形落在整像素网格上，更接近 MobaXterm 的清透锐利感。
                aaMode = RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
                fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_OFF;
            } else {
                // macOS/Linux SwingNode 用灰度 AA（LCD AA 在 macOS 上无效）
                aaMode = RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
                fractionalMetrics = RenderingHints.VALUE_FRACTIONALMETRICS_ON;
            }
            gfx.setRenderingHints(Map.of(
                    RenderingHints.KEY_TEXT_ANTIALIASING, aaMode,
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY,
                    RenderingHints.KEY_FRACTIONALMETRICS, fractionalMetrics,
                    RenderingHints.KEY_TEXT_LCD_CONTRAST, 180
            ));
        }
    }

    @Override
    public void setCoordAccessor(TerminalCoordinates coordAccessor) {
        this.capturedCoordAccessor = coordAccessor;
        super.setCoordAccessor(coordAccessor);
    }

    /**
     * 返回终端光标在 Swing 组件内的像素坐标（用于 IME 候选窗定位）。
     * 复刻 JediTerm MyInputMethodRequests.getTextLocation 的算法：
     *   x = cursorX * charWidth + insetX
     *   y = cursorY * charHeight
     * JediTerminal 的 cursorY 从 1 开始，因此直接乘行高已经是光标行下边缘；
     * 再加 1 会让候选窗固定向下偏移一整行。
     * 光标坐标通过 capturedCoordAccessor 获取（JediTermWidget init 时注入）。
     * 未初始化时返回 (0,0)，候选窗退化为组件左上角。
     */
    public Point getCursorLocationInComponent() {
        TerminalCoordinates coord = capturedCoordAccessor;
        if (coord == null || myCharSize.width <= 0 || myCharSize.height <= 0) {
            return new Point(0, 0);
        }
        return cursorAnchor(
                coord.getX(),
                coord.getY(),
                myCharSize.width,
                myCharSize.height,
                getInsetX()
        );
    }

    static Point cursorAnchor(int cursorX, int cursorY, int charWidth, int charHeight, int insetX) {
        int x = Math.max(0, cursorX) * Math.max(0, charWidth) + Math.max(0, insetX);
        int y = Math.max(0, cursorY) * Math.max(0, charHeight);
        return new Point(x, y);
    }

    @Override
    protected JPopupMenu createPopupMenu(TerminalActionProvider provider) {
        if (popupPresenter != null) {
            java.util.List<PopupAction> actions = new java.util.ArrayList<>();
            TerminalAction.buildMenu(provider, new com.jediterm.terminal.ui.TerminalActionMenuBuilder() {
                public void addAction(TerminalAction action) {
                    if (action == null || action.isHidden()) return;
                    String raw = action.getName();
                    actions.add(new PopupAction(ACTION_KEY_MAP.containsKey(raw) ? i18n.apply(ACTION_KEY_MAP.get(raw)) : raw,
                            shortcutText(action), action.isEnabled(null), false, () -> action.actionPerformed(null)));
                }
                public void addSeparator() { actions.add(new PopupAction("", "", false, true, null)); }
            });
            return new JPopupMenu() {
                @Override public void show(java.awt.Component invoker, int x, int y) {
                    popupPresenter.show(invoker, x, y, java.util.List.copyOf(actions));
                }
            };
        }
        Color bg      = jlshellSettings.backgroundColor();
        Color fg      = jlshellSettings.foregroundColor();
        Color hover   = blend(bg, fg, 0.12f);
        Color border  = blend(bg, fg, 0.22f);
        Color disabled = blend(bg, fg, 0.4f);

        // 菜单是 UI 元素，用系统 UI 字体而非终端等宽字体
        String os = System.getProperty("os.name", "").toLowerCase();
        String uiFamily;
        if (os.contains("win")) {
            uiFamily = "Microsoft YaHei";
        } else if (os.contains("mac")) {
            uiFamily = "PingFang SC";
        } else {
            uiFamily = Font.SANS_SERIF;
        }
        final Font menuFont = new Font(uiFamily, Font.PLAIN, 12);

        // 自绘 JPopupMenu：圆角背景 + 边框，不依赖 LAF 默认渲染
        JPopupMenu menu = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }

            @Override
            protected void paintBorder(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }

            @Override
            public void setVisible(boolean b) {
                if (b) {
                    SwingUtilities.invokeLater(() -> {
                        Window w = SwingUtilities.getWindowAncestor(this);
                        if (w != null) {
                            w.setBackground(new Color(0, 0, 0, 0));
                        }
                    });
                }
                super.setVisible(b);
            }
        };
        menu.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        menu.setOpaque(false);

        TerminalAction.buildMenu(provider, new com.jediterm.terminal.ui.TerminalActionMenuBuilder() {
            @Override
            public void addAction(TerminalAction action) {
                if (action == null || action.isHidden()) return;
                String rawName = action.getName();
                String label   = ACTION_KEY_MAP.containsKey(rawName)
                        ? i18n.apply(ACTION_KEY_MAP.get(rawName))
                        : rawName;
                String shortcut = shortcutText(action);
                boolean enabled = action.isEnabled(null);

                // 自绘菜单项：JPanel 整行绘制 hover 背景 + 左右边框线，
                // JLabel 只负责文字渲染。完全绕开 LAF 颜色覆盖。
                boolean[] hovered = {false};
                JPanel item = new JPanel(new java.awt.BorderLayout()) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        // 填充整行背景（普通/hover）
                        g.setColor(hovered[0] && enabled ? hover : bg);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        // 左右边框线，与 JPopupMenu 外框颜色一致
                        g.setColor(border);
                        g.drawLine(0, 0, 0, getHeight() - 1);
                        g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight() - 1);
                    }
                };
                item.setOpaque(false);

                javax.swing.JLabel textLabel = new javax.swing.JLabel(label);
                textLabel.setFont(menuFont);
                textLabel.setForeground(enabled ? fg : disabled);
                textLabel.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 28));
                item.add(textLabel, java.awt.BorderLayout.CENTER);

                if (!shortcut.isBlank()) {
                    javax.swing.JLabel shortcutLabel = new javax.swing.JLabel(shortcut);
                    shortcutLabel.setFont(menuFont);
                    shortcutLabel.setForeground(enabled ? blend(bg, fg, 0.62f) : disabled);
                    shortcutLabel.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
                    shortcutLabel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 16));
                    item.add(shortcutLabel, java.awt.BorderLayout.EAST);
                }

                item.setPreferredSize(new java.awt.Dimension(230, 34));

                if (enabled) {
                    item.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                    item.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                            hovered[0] = true;
                            item.repaint();
                        }
                        @Override public void mouseExited(java.awt.event.MouseEvent e) {
                            hovered[0] = false;
                            item.repaint();
                        }
                        @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                            action.actionPerformed(null);
                            // 关闭菜单
                            menu.setVisible(false);
                        }
                    });
                }
                menu.add(item);
            }

            @Override
            public void addSeparator() {
                // 自绘分隔线：用不透明 JPanel 填满背景遮住 LAF 默认装饰，
                // 画左右边框线 + 中间横线分隔
                javax.swing.JPanel sepPanel = new javax.swing.JPanel(null) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        // 用菜单背景色填满整行，遮住 LAF 画的竖线装饰
                        g.setColor(bg);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        // 左右边框线，与菜单外框一致
                        g.setColor(border);
                        g.drawLine(0, 0, 0, getHeight() - 1);
                        g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight() - 1);
                        // 中间横线分隔，左右留 8px 边距
                        int y = getHeight() / 2;
                        g.drawLine(8, y, getWidth() - 8, y);
                    }
                };
                sepPanel.setOpaque(true);
                sepPanel.setBackground(bg);
                sepPanel.setPreferredSize(new java.awt.Dimension(100, 9));
                menu.add(sepPanel);
            }
        });

        return menu;
    }

    private static String shortcutText(TerminalAction action) {
        java.util.List<KeyStroke> strokes = action.getPresentation().getKeyStrokes();
        if (strokes.isEmpty()) {
            return "";
        }
        return strokes.stream()
                .map(RefreshableTerminalPanel::formatKeyStroke)
                .filter(text -> !text.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    private static String formatKeyStroke(KeyStroke stroke) {
        if (stroke == null) {
            return "";
        }
        int modifiers = stroke.getModifiers();
        int keyCode = stroke.getKeyCode();
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            return "";
        }

        java.util.List<String> parts = new java.util.ArrayList<>();
        if (isMac()) {
            if ((modifiers & KeyEvent.META_DOWN_MASK) != 0) parts.add("⌘");
            if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0) parts.add("⌃");
            if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0) parts.add("⌥");
            if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0) parts.add("⇧");
            parts.add(macKeyText(keyCode));
            return String.join("", parts);
        }

        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0) parts.add("Ctrl");
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0) parts.add("Alt");
        if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0) parts.add("Shift");
        if ((modifiers & KeyEvent.META_DOWN_MASK) != 0) parts.add("Meta");
        parts.add(nonMacKeyText(keyCode));
        return String.join("+", parts);
    }

    private static String macKeyText(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_PAGE_UP -> "Page Up";
            case KeyEvent.VK_PAGE_DOWN -> "Page Down";
            case KeyEvent.VK_INSERT -> "Ins";
            case KeyEvent.VK_DELETE -> "Del";
            case KeyEvent.VK_BACK_SPACE -> "⌫";
            case KeyEvent.VK_ENTER -> "↩";
            case KeyEvent.VK_ESCAPE -> "Esc";
            default -> KeyEvent.getKeyText(keyCode);
        };
    }

    private static String nonMacKeyText(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_PAGE_UP -> "PgUp";
            case KeyEvent.VK_PAGE_DOWN -> "PgDn";
            case KeyEvent.VK_INSERT -> "Ins";
            case KeyEvent.VK_DELETE -> "Del";
            case KeyEvent.VK_UP -> "Up";
            case KeyEvent.VK_DOWN -> "Down";
            case KeyEvent.VK_LEFT -> "Left";
            case KeyEvent.VK_RIGHT -> "Right";
            default -> KeyEvent.getKeyText(keyCode);
        };
    }

    /** 在 color a 和 b 之间线性插值，ratio=0 返回 a，ratio=1 返回 b */
    private static Color blend(Color a, Color b, float ratio) {
        float r = 1f - ratio;
        return new Color(
                Math.round(a.getRed()   * r + b.getRed()   * ratio),
                Math.round(a.getGreen() * r + b.getGreen() * ratio),
                Math.round(a.getBlue()  * r + b.getBlue()  * ratio)
        );
    }

    /**
     * 拦截键盘事件，在分发给 TerminalKeyHandler 之前过滤噪音。
     *
     * macOS SwingNode 下的两类问题：
     *
     * 1. ESC：只产生 KEY_PRESSED/KEY_RELEASED，没有 KEY_TYPED。
     *    KEY_PRESSED(VK_ESCAPE) 的 keyChar='\0'（NUL），JediTerm 没有 ESC 的 keycode 映射，
     *    走到 isISOControl('\0')=true → processCharacter → 发送 NUL → 终端显示 ^@。
     *    修复：把 keyChar 替换为 '\u001B'，KEY_RELEASED 直接丢弃。
     *
     * 2. Command（⌘/Meta）组合键：同样只有 KEY_PRESSED，keyChar='\0'，
     *    JediTerm 把 NUL 发给终端 → 终端显示 ^@。
     *    修复：带 META 修饰符的 KEY_PRESSED/KEY_RELEASED 直接丢弃，不发给终端。
     *    （Cmd 组合键是 macOS 应用级快捷键，不应传入终端）
     */
    @Override
    public void processKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED) stopScrolling();
        int id = e.getID();
        int code = e.getKeyCode();
        char c = e.getKeyChar();

        if (id == KeyEvent.KEY_TYPED && ignoreNextShiftInsertTyped) {
            ignoreNextShiftInsertTyped = false;
            return;
        }
        if (id == KeyEvent.KEY_PRESSED && isShiftInsert(e)) {
            KeyEvent pasteEvent = new KeyEvent(
                    (java.awt.Component) e.getSource(),
                    KeyEvent.KEY_PRESSED,
                    e.getWhen(),
                    KeyEvent.SHIFT_DOWN_MASK,
                    KeyEvent.VK_INSERT,
                    KeyEvent.CHAR_UNDEFINED,
                    e.getKeyLocation()
            );
            if (TerminalAction.processEvent(this, pasteEvent)) {
                ignoreNextShiftInsertTyped = true;
                e.consume();
                return;
            }
            ignoreNextShiftInsertTyped = true;
            return;
        }
        if (id == KeyEvent.KEY_RELEASED && isShiftInsert(e)) {
            e.consume();
            return;
        }

        // 修饰键单独按下/释放不发送给终端
        if (id != KeyEvent.KEY_TYPED && isModifierOnly(code)) {
            return;
        }
        // KEY_TYPED 里的 NUL/UNDEFINED 是修饰键副作用或 IME composing 噪音，过滤掉
        if (id == KeyEvent.KEY_TYPED && (c == KeyEvent.CHAR_UNDEFINED || c == '\0')) {
            return;
        }
        // Windows IME composing 期间产生的 KEY_PRESSED keyChar='\0' 且 keyCode=0，
        // JediTerm 会当作 isISOControl('\0') → processCharacter → 发送 NUL → 显示 ^@。
        // 正常功能键（Backspace、方向键、Ctrl+C 等）keyCode 有明确值，不受此过滤影响。
        if (isWindows() && id == KeyEvent.KEY_PRESSED && c == '\0' && code == KeyEvent.VK_UNDEFINED) {
            return;
        }

        // macOS Command（⌘）组合键：默认不发给终端，避免 SwingNode 传入 NUL 显示 ^@。
        // 但 JediTerm 自带的复制/粘贴/查找/清屏/滚动动作需要看到 KEY_PRESSED，
        // 所以这些组合键放行给 super.processKeyEvent() 做 TerminalAction 匹配。
        boolean hasMeta = (e.getModifiersEx() & KeyEvent.META_DOWN_MASK) != 0;
        if (hasMeta && id != KeyEvent.KEY_TYPED && !isMacMetaTerminalAction(id, code)) {
            return;
        }

        // macOS SwingNode 下 ESC 的 KEY_PRESSED keyChar='\0'，需要修正为 '\u001B'
        if (id == KeyEvent.KEY_PRESSED && code == KeyEvent.VK_ESCAPE && c != '\u001B') {
            e = new KeyEvent(
                    (java.awt.Component) e.getSource(),
                    e.getID(), e.getWhen(), e.getModifiersEx(),
                    e.getKeyCode(), '\u001B', e.getKeyLocation()
            );
        }
        // ESC 的 KEY_RELEASED 不需要发送任何内容
        if (id == KeyEvent.KEY_RELEASED && code == KeyEvent.VK_ESCAPE) {
            return;
        }

        // Windows SwingNode forwards Ctrl+letter KEY_PRESSED with a wrong keyChar
        // ('\0' or 'U' from JavaFX "Undefined" string), but the keyCode (VK_A..VK_Z)
        // is correct. JediTerm's TerminalKeyEncoder has no Ctrl+letter entries, so
        // it falls into the isISOControl(keyChar) branch and sends the wrong byte —
        // Ctrl+C/D etc. either do nothing or send NUL.
        // Fix: when Ctrl is held (no Alt/Meta) and keyCode is A-Z, rewrite keyChar
        // to the matching control character (Ctrl+A= ... Ctrl+Z=) so
        // JediTerm's processCharacter sends the right byte.
        if (id == KeyEvent.KEY_PRESSED
                && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0
                && (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) == 0
                && (e.getModifiersEx() & KeyEvent.META_DOWN_MASK) == 0) {
            int controlChar = ctrlToControlChar(code);
            if (controlChar >= 0 && c != (char) controlChar) {
                e = new KeyEvent(
                        (java.awt.Component) e.getSource(),
                        e.getID(), e.getWhen(), e.getModifiersEx(),
                        e.getKeyCode(), (char) controlChar, e.getKeyLocation()
                );
            }
        }

        super.processKeyEvent(e);
    }

    /**
     * Map common terminal Ctrl chords to control characters.
     * Examples: Ctrl+A=1, Ctrl+Space=0, Ctrl+[=ESC, Ctrl+\=FS, Ctrl+?=DEL.
     * Non terminal control chords return -1.
     */
    private static int ctrlToControlChar(int keyCode) {
        if (keyCode == KeyEvent.VK_SPACE || keyCode == KeyEvent.VK_2) {
            return 0x00;
        }
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            return keyCode - KeyEvent.VK_A + 1;
        }
        return switch (keyCode) {
            case KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_3 -> 0x1B;
            case KeyEvent.VK_BACK_SLASH, KeyEvent.VK_4 -> 0x1C;
            case KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_5 -> 0x1D;
            case KeyEvent.VK_6 -> 0x1E;
            case KeyEvent.VK_MINUS, KeyEvent.VK_7 -> 0x1F;
            case KeyEvent.VK_SLASH, KeyEvent.VK_8 -> 0x7F;
            default -> -1;
        };
    }

    private static boolean isMacMetaTerminalAction(int eventId, int keyCode) {
        if (eventId != KeyEvent.KEY_PRESSED) {
            return false;
        }
        return keyCode == KeyEvent.VK_C
                || keyCode == KeyEvent.VK_V
                || keyCode == KeyEvent.VK_F
                || keyCode == KeyEvent.VK_K
                || keyCode == KeyEvent.VK_UP
                || keyCode == KeyEvent.VK_DOWN;
    }

    private static boolean isShiftInsert(KeyEvent e) {
        return e.getKeyCode() == KeyEvent.VK_INSERT
                && (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0
                && (e.getModifiersEx() & (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK | KeyEvent.META_DOWN_MASK)) == 0;
    }

    private static boolean isModifierOnly(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META
                || keyCode == KeyEvent.VK_CAPS_LOCK
                || keyCode == KeyEvent.VK_NUM_LOCK
                || keyCode == KeyEvent.VK_SCROLL_LOCK;
    }

    /**
     * 中文及中文标点优先使用用户指定的中文字体；留空或缺少字形时回退系统 CJK 字体。
     * 英文保留主字体、终端网格宽度和粗斜体样式。
     * <ul>
     *   <li>Windows → "Microsoft YaHei"</li>
     *   <li>macOS   → "PingFang SC"</li>
     *   <li>Linux   → "Noto Sans CJK SC" / "WenQuanYi Micro Hei"</li>
     * </ul>
     */
    @Override
    protected @NotNull Font getFontToDisplay(char[] text, int start, int end, @NotNull TextStyle style) {
        drawingStyle = style;
        Font baseFont = super.getFontToDisplay(text, start, end, style);
        if (jlshellSettings == null) return baseFont;
        for (int i = start; i < end;) {
            int codePoint = Character.codePointAt(text, i, end);
            if (codePoint != CharUtils.DWC && (TerminalFontResolver.isChinese(codePoint) || !baseFont.canDisplay(codePoint)))
                return jlshellSettings.getTerminalFontFor(codePoint, baseFont.getStyle());
            i += Character.charCount(codePoint);
        }
        return baseFont;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
