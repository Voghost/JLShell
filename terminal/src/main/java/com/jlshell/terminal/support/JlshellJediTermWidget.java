package com.jlshell.terminal.support;

import com.jediterm.terminal.SubstringFinder;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermSearchComponent;
import com.jediterm.terminal.ui.JediTermSearchComponentListener;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 对 JediTermWidget 的轻量扩展。
 * 主要用于暴露刷新字体/主题的入口，以及自定义滚动条和搜索栏样式。
 */
public class JlshellJediTermWidget extends JediTermWidget {

    private static final ThreadLocal<JlshellSettingsProvider> CONSTRUCTION_SETTINGS = new ThreadLocal<>();
    private static final ThreadLocal<Function<String, String>> CONSTRUCTION_I18N = new ThreadLocal<>();

    private final JlshellSettingsProvider settingsProvider;
    private final Function<String, String> i18n;
    private RefreshableTerminalPanel terminalPanel;
    private JScrollBar themedScrollBar;

    private JlshellJediTermWidget(int columns, int rows, JlshellSettingsProvider settingsProvider,
                                  Function<String, String> i18n) {
        super(columns, rows, settingsProvider);
        this.settingsProvider = settingsProvider;
        this.i18n = i18n;
    }

    public static JlshellJediTermWidget create(int columns, int rows, JlshellSettingsProvider settingsProvider,
                                               Function<String, String> i18n) {
        CONSTRUCTION_SETTINGS.set(settingsProvider);
        CONSTRUCTION_I18N.set(i18n);
        try {
            return new JlshellJediTermWidget(columns, rows, settingsProvider, i18n);
        } finally {
            CONSTRUCTION_SETTINGS.remove();
            CONSTRUCTION_I18N.remove();
        }
    }

    @Override
    protected StyleState createDefaultStyle() {
        StyleState styleState = new StyleState();
        styleState.setDefaultStyle(activeSettingsProvider().defaultTextStyle());
        return styleState;
    }

    @Override
    protected TerminalPanel createTerminalPanel(
            SettingsProvider settingsProvider,
            StyleState styleState,
            TerminalTextBuffer terminalTextBuffer
    ) {
        JlshellSettingsProvider sp = CONSTRUCTION_SETTINGS.get();
        Function<String, String> i18nFn = CONSTRUCTION_I18N.get();
        this.terminalPanel = new RefreshableTerminalPanel(settingsProvider, terminalTextBuffer, styleState, sp, i18nFn);
        return terminalPanel;
    }

    @Override
    protected JediTerminal createTerminal(
            TerminalDisplay terminalDisplay,
            TerminalTextBuffer terminalTextBuffer,
            StyleState styleState
    ) {
        styleState.setDefaultStyle(activeSettingsProvider().defaultTextStyle());
        return super.createTerminal(terminalDisplay, terminalTextBuffer, styleState);
    }

    // ── 自定义滚动条：主题色、细宽度、圆角 thumb ──────────────────────

    @Override
    protected JScrollBar createScrollBar() {
        JScrollBar scrollBar = new JScrollBar();
        scrollBar.setUI(new ThemedScrollBarUI());
        scrollBar.setOpaque(false);
        scrollBar.setBorder(BorderFactory.createEmptyBorder());
        themedScrollBar = scrollBar;
        return scrollBar;
    }

    /**
     * 主题色滚动条 UI。
     * 继承 BasicScrollBarUI 保留拖动/点击交互，只覆盖绘制方法。
     * - track 透明，仅绘制搜索匹配标记
     * - thumb 圆角，半透明主题色
     */
    private class ThemedScrollBarUI extends BasicScrollBarUI {

        ThemedScrollBarUI() {}

        @Override
        protected void configureScrollBarColors() {
            scrollBarWidth = 8;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createZeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createZeroButton();
        }

        private JButton createZeroButton() {
            JButton btn = new JButton();
            btn.setPreferredSize(new Dimension(0, 0));
            btn.setMinimumSize(new Dimension(0, 0));
            btn.setMaximumSize(new Dimension(0, 0));
            btn.setVisible(false);
            return btn;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // track 透明 — 不绘制背景
            // 但需要绘制搜索匹配标记
            if (terminalPanel == null) return;
            com.jediterm.terminal.ui.TerminalPanel panel = getTerminalPanel();
            com.jediterm.terminal.SubstringFinder.FindResult result = panel.getFindResult();
            if (result == null) return;

            JScrollBar sb = (JScrollBar) c;
            int min = sb.getMinimum();
            int max = sb.getMaximum();
            int range = max - min;
            if (range <= 0) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color markerColor = settingsProvider.foregroundColor();
            g2.setColor(new Color(markerColor.getRed(), markerColor.getGreen(), markerColor.getBlue(), 70));
            int markerH = Math.max(2, trackBounds.height / range);
            for (com.jediterm.terminal.SubstringFinder.FindResult.FindItem item : result.getItems()) {
                int y = trackBounds.y + trackBounds.height * item.getStart().y / range;
                g2.fillRoundRect(trackBounds.x + 1, y, 6, markerH, 2, 2);
            }
            g2.dispose();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !c.isEnabled()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color bg = settingsProvider.backgroundColor();
            Color fg = settingsProvider.foregroundColor();
            float alpha = isThumbRollover() ? 0.38f : 0.22f;
            Color thumbColor = blend(bg, fg, alpha);
            g2.setColor(thumbColor);
            g2.fillRoundRect(thumbBounds.x + 1, thumbBounds.y, 6, thumbBounds.height, 4, 4);

            g2.dispose();
        }

        private Color blend(Color a, Color b, float ratio) {
            float r = 1f - ratio;
            return new Color(
                    Math.round(a.getRed()   * r + b.getRed()   * ratio),
                    Math.round(a.getGreen() * r + b.getGreen() * ratio),
                    Math.round(a.getBlue()  * r + b.getBlue()  * ratio)
            );
        }
    }

    // ── 自定义搜索组件：主题色适配 ──────────────────────────────────

    @Override
    protected @NotNull JediTermSearchComponent createSearchComponent() {
        return new ThemedSearchComponent();
    }

    /**
     * 主题色搜索栏。
     * 完全替代 JediTerm 默认的白色搜索栏。
     *
     * 关键：JediTerm 调用 getComponent().requestFocus() 和 addKeyListener()，
     * 所以 JPanel 必须设为 focusable，同时把键盘事件代理到 textField。
     */
    private class ThemedSearchComponent extends JPanel implements JediTermSearchComponent {

        private final JTextField textField = new JTextField();
        private final JLabel resultLabel = new JLabel();
        private final JCheckBox ignoreCaseCheckBox;
        private final List<JediTermSearchComponentListener> listeners = new CopyOnWriteArrayList<>();
        private final JediTermSearchComponentListener multicaster = createMulticaster();

        ThemedSearchComponent() {
            Color bg = settingsProvider.backgroundColor();
            Color fg = settingsProvider.foregroundColor();
            Color fieldBg = blend(bg, fg, 0.08f);
            Color borderColor = blend(bg, fg, 0.25f);

            setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
            // 自绘圆角背景 + 边框
            setOpaque(false);
            setFocusable(true);

            // 文本输入框
            Font termFont = settingsProvider.getTerminalFont();
            FontMetrics fm = getFontMetrics(termFont);
            int charW = fm != null ? fm.charWidth('W') : 8;
            int charH = fm != null ? fm.getHeight() : 16;
            textField.setPreferredSize(new Dimension(charW * 30, charH + 6));
            textField.setEditable(true);
            textField.setBackground(fieldBg);
            textField.setForeground(fg);
            textField.setCaretColor(fg);
            textField.setFont(termFont);
            textField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor, 1, true),
                    BorderFactory.createEmptyBorder(2, 6, 2, 6)));
            textField.setSelectedTextColor(bg);
            textField.setSelectionColor(blend(bg, fg, 0.3f));
            textField.addFocusListener(new FocusListener() {
                @Override public void focusGained(FocusEvent e) { textField.selectAll(); }
                @Override public void focusLost(FocusEvent e) {}
            });
            // 把 JPanel 的焦点代理到 textField
            addFocusListener(new FocusListener() {
                @Override public void focusGained(FocusEvent e) { textField.requestFocusInWindow(); }
                @Override public void focusLost(FocusEvent e) {}
            });
            add(textField);

            // 忽略大小写
            String ignoreCaseText = i18n != null ? i18n.apply("terminal.search.ignoreCase") : "Ignore Case";
            ignoreCaseCheckBox = new JCheckBox(ignoreCaseText, true);
            ignoreCaseCheckBox.setOpaque(false);
            ignoreCaseCheckBox.setForeground(fg);
            ignoreCaseCheckBox.setBorder(BorderFactory.createEmptyBorder());
            ignoreCaseCheckBox.setFocusPainted(false);
            add(ignoreCaseCheckBox);

            // 结果标签
            resultLabel.setForeground(blend(bg, fg, 0.6f));
            add(resultLabel);

            // 上/下按钮
            add(createNavButton("▲", () -> multicaster.selectPrevFindResult()));
            add(createNavButton("▼", () -> multicaster.selectNextFindResult()));

            // 关闭按钮
            add(createCloseButton());

            listenForChanges();
        }

        /** 自绘圆角背景和边框 */
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg = settingsProvider.backgroundColor();
            Color borderColor = blend(bg, settingsProvider.foregroundColor(), 0.25f);
            int arc = 10;
            // 背景
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            // 边框
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }

        private JButton createCloseButton() {
            Color bg = settingsProvider.backgroundColor();
            Color fg = settingsProvider.foregroundColor();
            Color hoverBg = blend(bg, fg, 0.15f);

            JButton btn = new JButton("✕");
            btn.setBackground(bg);
            btn.setForeground(fg);
            btn.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            btn.setFocusPainted(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(true);
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 12f));
            btn.addActionListener(e -> multicaster.hideSearchComponent());
            btn.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(hoverBg); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { btn.setBackground(bg); }
            });
            return btn;
        }

        private JButton createNavButton(String text, Runnable action) {
            Color bg = settingsProvider.backgroundColor();
            Color fg = settingsProvider.foregroundColor();
            Color hoverBg = blend(bg, fg, 0.15f);

            JButton btn = new JButton(text);
            btn.setBackground(bg);
            btn.setForeground(fg);
            btn.setBorder(BorderFactory.createLineBorder(blend(bg, fg, 0.25f), 1, true));
            btn.setFocusPainted(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(true);
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
            btn.addActionListener(e -> action.run());
            btn.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(hoverBg); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { btn.setBackground(bg); }
            });
            return btn;
        }

        private void listenForChanges() {
            Runnable settingsChanged = () ->
                    multicaster.searchSettingsChanged(textField.getText(), ignoreCaseCheckBox.isSelected());
            textField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { settingsChanged.run(); }
                @Override public void removeUpdate(DocumentEvent e) { settingsChanged.run(); }
                @Override public void changedUpdate(DocumentEvent e) { settingsChanged.run(); }
            });
            ignoreCaseCheckBox.addItemListener(e -> settingsChanged.run());
        }

        private void updateLabel(@Nullable SubstringFinder.FindResult result) {
            if (result == null) {
                resultLabel.setText("");
            } else if (!result.getItems().isEmpty()) {
                SubstringFinder.FindResult.FindItem selectedItem = result.selectedItem();
                resultLabel.setText(selectedItem.getIndex() + " / " + result.getItems().size());
            }
        }

        @Override
        public void onResultUpdated(SubstringFinder.@Nullable FindResult results) {
            updateLabel(results);
        }

        @Override
        public @NotNull JComponent getComponent() { return this; }

        @Override
        public void addListener(@NotNull JediTermSearchComponentListener listener) {
            listeners.add(listener);
        }

        /**
         * JediTerm 调用 addKeyListener() 注册 ESC/ENTER/UP/DOWN 快捷键。
         * 因为键盘输入实际发生在 textField，所以转发到 textField。
         */
        @Override
        public void addKeyListener(@NotNull KeyListener listener) {
            textField.addKeyListener(listener);
        }

        private @NotNull JediTermSearchComponentListener createMulticaster() {
            final Class<JediTermSearchComponentListener> cls = JediTermSearchComponentListener.class;
            return (JediTermSearchComponentListener) java.lang.reflect.Proxy.newProxyInstance(
                    cls.getClassLoader(), new Class[]{cls}, (obj, method, params) -> {
                        for (JediTermSearchComponentListener l : listeners) {
                            method.invoke(l, params);
                        }
                        return null;
                    });
        }

        private Color blend(Color a, Color b, float ratio) {
            float r = 1f - ratio;
            return new Color(
                    Math.round(a.getRed()   * r + b.getRed()   * ratio),
                    Math.round(a.getGreen() * r + b.getGreen() * ratio),
                    Math.round(a.getBlue()  * r + b.getBlue()  * ratio)
            );
        }
    }

    // ── 刷新视觉 ──────────────────────────────────────────────────

    public void refreshVisuals() {
        java.awt.Color bg = settingsProvider.backgroundColor();
        java.awt.Color fg = settingsProvider.foregroundColor();
        double opacity = settingsProvider.opacity();
        boolean transparent = opacity < 1.0;
        java.awt.Color effectiveBg = transparent
                ? new java.awt.Color(bg.getRed(), bg.getGreen(), bg.getBlue(), (int) (opacity * 255))
                : bg;
        setBackground(effectiveBg);
        setOpaque(!transparent);
        if (terminalPanel != null) {
            terminalPanel.setBackground(effectiveBg);
            terminalPanel.setForeground(fg);
            terminalPanel.setOpaque(!transparent);
            terminalPanel.refreshVisuals();
        }
        getTerminalPanel().setBackground(effectiveBg);
        getTerminalPanel().setForeground(fg);
        getTerminalPanel().revalidate();
        getTerminalPanel().repaint();
        if (themedScrollBar != null) {
            themedScrollBar.repaint();
        }
        revalidate();
        repaint();
    }

    private JlshellSettingsProvider activeSettingsProvider() {
        if (settingsProvider != null) {
            return settingsProvider;
        }
        JlshellSettingsProvider constructingProvider = CONSTRUCTION_SETTINGS.get();
        if (constructingProvider != null) {
            return constructingProvider;
        }
        throw new IllegalStateException("JediTerm settings provider is not available during widget initialization");
    }
}
