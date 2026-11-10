package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Settings UI DSL style rows: related controls share a baseline; comments follow their controls. */
public final class SwingSettingsGeneralPage {
    private SwingSettingsGeneralPage() {}

    public record Values(String language, String theme, com.jlshell.ui.theme.AccentColor accent, String fontFamily,
                         int fontSize, int timeout, int keepAlive, boolean autoUpdate, String channel, String baseUrl) {}

    public static JComponent create(Values initial, java.util.List<String> fontNames, I18nService i18n,
                                     com.jlshell.ui.service.MemoryReclaimService memory, java.util.function.Consumer<Values> changed) {
        Map<String, JComponent> controls = new LinkedHashMap<>();
        JComboBox<String> language = new JComboBox<>(new String[]{"English", "中文 (简体)"});
        language.setSelectedIndex("zh_CN".equals(initial.language()) ? 1 : 0);
        JComboBox<String> theme = new JComboBox<>(new String[]{"Dark", "Light"}); theme.setName("settings-theme");
        theme.setSelectedIndex("LIGHT".equals(initial.theme()) ? 1 : 0);
        JComboBox<com.jlshell.ui.theme.AccentColor> accent = new JComboBox<>(com.jlshell.ui.theme.AccentColor.values());
        accent.setName("settings-accent"); accent.setSelectedItem(initial.accent());
        accent.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : "●  " + value.displayName());
            label.setOpaque(true); label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(value == null ? list.getForeground() : Color.decode(value.color())); return label;
        });
        String defaultFont = i18n.get("preferences.general.uiFont.default");
        var families = new java.util.ArrayList<>(fontNames); families.addFirst(defaultFont);
        JComboBox<String> font = new JComboBox<>(families.toArray(String[]::new)); font.setName("settings-ui-font");
        font.putClientProperty("jlshell.fontPicker", true); font.setMaximumRowCount(14);
        var monospaced = fontNames.stream().filter(name -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("mono") || lower.contains("code") || lower.contains("console") || lower.contains("courier") || lower.contains("typewriter");
        }).collect(java.util.stream.Collectors.toSet());
        font.setRenderer(new com.jlshell.ui.swing.idea.IdeaComboBoxRenderer(String::valueOf, true, true, monospaced,
                i18n.get("preferences.font.monospaced"), i18n.get("preferences.font.proportional")));
        font.setSelectedItem(initial.fontFamily() == null || initial.fontFamily().isBlank() ? defaultFont : initial.fontFamily());
        JSpinner size = new JSpinner(new SpinnerNumberModel(initial.fontSize(), 8, 24, 1)); size.setName("settings-size-8-24");
        JLabel preview = new JLabel("Hello World  Settings  设置  AaBbCc 0123");
        JTextField timeout = new JTextField(Integer.toString(initial.timeout())); timeout.setName("settings-connection-timeout");
        JTextField keepAlive = new JTextField(Integer.toString(initial.keepAlive())); keepAlive.setName("settings-connection-keepAlive");
        int[] lastValidTimeout = {initial.timeout()}, lastValidKeepAlive = {initial.keepAlive()};
        JCheckBox autoUpdate = new JCheckBox(i18n.get("preferences.updates.autoCheck"), initial.autoUpdate());
        JComboBox<String> channel = new JComboBox<>(new String[]{"stable", "beta"}); channel.setSelectedItem(initial.channel());
        JTextField url = new JTextField(initial.baseUrl());
        Runnable publish = () -> {
            String family = (String) font.getSelectedItem();
            if (defaultFont.equals(family)) family = null;
            Font base = UIManager.getFont("defaultFont");
            preview.setFont(family == null ? base.deriveFont(((Number) size.getValue()).floatValue())
                    : com.formdev.flatlaf.util.FontUtils.getCompositeFont(family, Font.PLAIN, ((Number) size.getValue()).intValue()));
            try { int value = Integer.parseInt(timeout.getText().strip()); if (value > 0) lastValidTimeout[0] = value; }
            catch (NumberFormatException ignored) {}
            try { int value = Integer.parseInt(keepAlive.getText().strip()); if (value >= 0) lastValidKeepAlive[0] = value; }
            catch (NumberFormatException ignored) {}
            changed.accept(new Values(language.getSelectedIndex() == 1 ? "zh_CN" : "en", theme.getSelectedIndex() == 1 ? "LIGHT" : "DARK",
                    (com.jlshell.ui.theme.AccentColor) accent.getSelectedItem(), family, ((Number) size.getValue()).intValue(),
                    lastValidTimeout[0], lastValidKeepAlive[0], autoUpdate.isSelected(),
                    (String) channel.getSelectedItem(), url.getText().isBlank() ? com.jlshell.ui.service.update.UpdateService.DEFAULT_BASE_URL : url.getText().strip()));
        };
        for (JComboBox<?> combo : java.util.List.of(language, theme, accent, font, channel)) combo.addActionListener(event -> publish.run());
        size.addChangeListener(event -> publish.run());
        autoUpdate.addActionListener(event -> publish.run());
        for (JTextField field : java.util.List.of(url, timeout, keepAlive)) field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { publish.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { publish.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { publish.run(); }
        });
        JButton gc = new JButton(i18n.get("preferences.general.memoryGc")); gc.setEnabled(memory != null);
        JLabel memoryStatus = new JLabel();
        gc.addActionListener(event -> {
            gc.setEnabled(false); memoryStatus.setText(i18n.get("preferences.general.memoryGc.running"));
            memory.requestNowWithStatus(message -> { gc.setEnabled(true); memoryStatus.setText(i18n.get("preferences.general.memoryGc.done", message)); });
        });
        JPanel memoryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); memoryRow.setOpaque(false); memoryRow.add(gc); memoryRow.add(memoryStatus);
        controls.put("language", language); controls.put("theme", theme); controls.put("accent", accent); controls.put("font", font); controls.put("size", size);
        controls.put("preview", preview); controls.put("timeout", timeout); controls.put("keepAlive", keepAlive); controls.put("memory", memoryRow);
        controls.put("autoUpdate", autoUpdate); controls.put("channel", channel); controls.put("url", url);
        publish.run();
            JPanel form=new JPanel(new GridBagLayout()); form.setOpaque(false);
            form.setBorder(BorderFactory.createEmptyBorder(16,24,32,24));
            form.setName("settings.generalForm");
            int row=0;
            add(form,row++,line(group(label(i18n,"preferences.general.theme"),sized(controls.get("theme"),200)),
                    gap(12),group(label(i18n,"preferences.general.accentColor"),sized(controls.get("accent"),132))),0);
            add(form,row++,line(label(i18n,"preferences.general.language"),sized(controls.get("language"),200)),12);

            add(form,row++,section(i18n.get("preferences.general.fontSection")),30);
            add(form,row++,line(group(label(i18n,"preferences.general.uiFontFamily"),sized(controls.get("font"),220)),
                    gap(12),group(label(i18n,"preferences.general.uiFontSize"),sized(controls.get("size"),68))),12);
            preview.setName("settings.fontPreview");
            add(form,row++,indented(line(preview),20),12);

            add(form,row++,section(i18n.get("preferences.connection.section")),30);
            add(form,row++,line(label(i18n,"preferences.connection.timeout"),sized(controls.get("timeout"),68),
                    new JLabel(i18n.get("preferences.connection.timeoutUnit"))),12);
            add(form,row++,line(label(i18n,"preferences.connection.keepAlive"),sized(controls.get("keepAlive"),68),
                    new JLabel(i18n.get("preferences.connection.keepAliveUnit"))),12);
            add(form,row++,indented(comment(i18n.get("preferences.connection.section.description")),20),8);

            add(form,row++,section(i18n.get("preferences.general.systemSection")),30);
            add(form,row++,line(controls.get("memory")),12);
            add(form,row++,indented(comment(i18n.get("preferences.general.systemSection.description")),20),8);

            add(form,row++,section(i18n.get("preferences.updates.title")),30);
            add(form,row++,line(controls.get("autoUpdate"),gap(12),label(i18n,"preferences.updates.channel"),
                    sized(controls.get("channel"),112)),12);
            add(form,row++,line(label(i18n,"preferences.updates.baseUrl"),sized(controls.get("url"),360)),12);
            add(form,row++,indented(comment(i18n.get("preferences.updates.description")),20),8);
            GridBagConstraints glue=new GridBagConstraints();glue.gridy=row;glue.weighty=1;glue.fill=GridBagConstraints.VERTICAL;
            form.add(Box.createVerticalGlue(),glue);
            JScrollPane scroll=new JScrollPane(new Content(form));
            scroll.setName("settings.generalScroll");scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            IdeaFormStyle.applyTree(form);
            return scroll;
    }

    private static void add(JPanel panel,int row,JComponent component,int top) {
        GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=row;c.weightx=1;
        c.anchor=GridBagConstraints.NORTHWEST;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(top,0,0,0);
        panel.add(component,c);
    }
    private static JPanel line(Component... components) {
        JPanel row=new JPanel(new WrappingRows());row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0,-6,0,0));
        for(Component component:components)row.add(component);
        return row;
    }
    private static JPanel group(Component... components){
        FlowLayout layout=new FlowLayout(FlowLayout.LEFT,6,0);layout.setAlignOnBaseline(true);
        JPanel group=new JPanel(layout);group.setBorder(BorderFactory.createEmptyBorder(0,-6,0,-6));group.setOpaque(false);for(Component component:components)group.add(component);return group;
    }
    private static final class WrappingRows extends FlowLayout {
        WrappingRows(){super(FlowLayout.LEFT,6,2);setAlignOnBaseline(true);}
        @Override public Dimension preferredLayoutSize(Container target){
            int width=target.getWidth();if(width<=0&&target.getParent()!=null)width=target.getParent().getWidth();
            if(width<=0)width=Integer.MAX_VALUE;
            Insets insets=target.getInsets();int available=Math.max(1,width-insets.left-insets.right-12),x=0,height=0,lineHeight=0;
            for(Component child:target.getComponents())if(child.isVisible()){
                Dimension d=child.getPreferredSize();if(x>0&&x+getHgap()+d.width>available){height+=lineHeight+getVgap();x=0;lineHeight=0;}
                x+=(x==0?0:getHgap())+d.width;lineHeight=Math.max(lineHeight,d.height);
            }
            return new Dimension(width==Integer.MAX_VALUE?x+12:width,height+lineHeight+getVgap()*2+insets.top+insets.bottom);
        }
    }
    private static JLabel label(I18nService i18n,String key) { return new JLabel(i18n.get(key)+"："); }
    private static Component gap(int width) { return Box.createHorizontalStrut(width); }
    private static JComponent sized(JComponent component,int width) {
        if(component instanceof JTextField text)text.setColumns(0);
        component.setPreferredSize(new Dimension(width,Math.max(24,component.getFontMetrics(component.getFont()).getHeight()+6)));
        return component;
    }
    private static JPanel section(String title) {
        JPanel panel=new JPanel(new BorderLayout(10,0));panel.setOpaque(false);
        JLabel label=new JLabel(title);label.setFont(UIManager.getFont("defaultFont").deriveFont(Font.BOLD, UIManager.getFont("defaultFont").getSize2D()+1));
        label.setBorder(BorderFactory.createEmptyBorder(0,0,6,0));
        label.setName("settings-section-"+title);
        panel.add(label,BorderLayout.WEST);
        JPanel line=new JPanel(new GridBagLayout());line.setOpaque(false);
        GridBagConstraints c=new GridBagConstraints();c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;
        line.add(new JSeparator(),c);panel.add(line,BorderLayout.CENTER);
        return panel;
    }
    private static JComponent comment(String text) {
        JTextArea comment=new JTextArea(text);comment.setLineWrap(true);comment.setWrapStyleWord(true);
        comment.setEditable(false);comment.setFocusable(false);comment.setOpaque(false);comment.setBorder(null);
        comment.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
        comment.putClientProperty("JLShell.secondaryForeground",true);comment.setForeground(SwingTheme.color("secondary"));
        comment.setColumns(50);comment.setRows(1);return comment;
    }
    private static JPanel indented(JComponent content,int indent) {
        JPanel panel=new JPanel(new BorderLayout());panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0,indent,0,0));panel.add(content);return panel;
    }
    private static final class Content extends JPanel implements Scrollable {
        Content(JPanel form) {super(new BorderLayout());setOpaque(false);add(form,BorderLayout.NORTH);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 16;}
        public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(16,r.height-16);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
}
