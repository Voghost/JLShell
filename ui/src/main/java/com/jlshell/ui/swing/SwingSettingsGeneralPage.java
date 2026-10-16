package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javafx.scene.Node;
import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Settings UI DSL style rows: related controls share a baseline; comments follow their controls. */
final class SwingSettingsGeneralPage {
    private SwingSettingsGeneralPage() {}

    @SuppressWarnings("unchecked")
    static Supplier<JComponent> prepare(Node model, SwingFormRenderer renderer, I18nService i18n) {
        Map<String,Node> fields=(Map<String,Node>)model.getProperties().get("jlshell.generalSettingsFields");
        Map<String,Supplier<JComponent>> factories=new LinkedHashMap<>();
        fields.forEach((key,node)->factories.put(key,renderer.prepare(node)));
        return () -> {
            Map<String,JComponent> controls=new LinkedHashMap<>();
            factories.forEach((key,factory)->controls.put(key,factory.get()));
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
            JComponent preview=controls.get("preview");
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
            return scroll;
        };
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
