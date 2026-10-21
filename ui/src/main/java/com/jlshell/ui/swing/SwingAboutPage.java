package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Dedicated product presentation, shared by Settings and Help/About. No FX rendering. */
final class SwingAboutPage {
    private SwingAboutPage() {}

    static Supplier<JComponent> prepare(SwingFormRenderer renderer, ScrollPane model, I18nService i18n) {
        List<Node> nodes=new ArrayList<>();collect(model.getContent(),nodes);
        String description=label(nodes,"about-description"), version=label(nodes,"about-version-badge");
        List<Supplier<JComponent>> links=nodes.stream().filter(n->n instanceof Button&&n.getStyleClass().contains("about-link-button")).map(renderer::prepare).toList();
        List<String> titles=nodes.stream().filter(n->n instanceof Label&&n.getStyleClass().contains("about-link-title")).map(n->((Label)n).getText()).toList();
        List<String> descriptions=nodes.stream().filter(n->n instanceof Label&&n.getStyleClass().contains("about-link-description")).map(n->((Label)n).getText()).toList();
        List<List<String>> info=new ArrayList<>();
        for(Node node:nodes)if(node.getStyleClass().contains("about-info-row")&&node instanceof Parent parent) {
            info.add(parent.getChildrenUnmodifiable().stream().filter(Label.class::isInstance).map(n->((Label)n).getText()).toList());
        }
        var copy=nodes.stream().filter(n->n instanceof Button&&n.getStyleClass().contains("about-runtime-copy")).findFirst().map(renderer::prepare).orElse(null);
        var updates=nodes.stream().filter(n->n instanceof Button&&n.getStyleClass().contains("about-compact-action")&&!n.getStyleClass().contains("about-runtime-copy")).findFirst().map(renderer::prepare).orElse(null);
        String resources=i18n.get("preferences.about.resources"),runtime=i18n.get("preferences.about.details"),footer=i18n.get("preferences.about.footer");
        return ()->{
            JPanel page=new JPanel(new GridBagLayout());page.setOpaque(false);page.setBorder(BorderFactory.createEmptyBorder(24,28,24,28));
            JPanel content=new JPanel(new GridBagLayout());content.setOpaque(false);
            GridBagConstraints row=new GridBagConstraints();row.gridx=0;row.gridy=0;row.weightx=1;row.fill=GridBagConstraints.HORIZONTAL;row.anchor=GridBagConstraints.NORTHWEST;
            Surface hero=new Surface(true);hero.setLayout(new BorderLayout(24,0));hero.setBorder(BorderFactory.createEmptyBorder(30,28,30,28));
            JLabel mark=new JLabel(new SwingBrandIcon(96));hero.add(mark,BorderLayout.WEST);
            JPanel identity=vertical(8);identity.add(heading("JLShell",36));identity.add(secondary(description,14));
            JPanel versionRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));versionRow.setOpaque(false);versionRow.add(secondary(version,12));
            if(updates!=null){versionRow.add(Box.createHorizontalStrut(16));JButton update=(JButton)updates.get();update.removeAll();update.setLayout(null);update.setIcon(new WorkbenchIcon(WorkbenchIcon.Kind.REFRESH));update.setContentAreaFilled(false);update.setBorderPainted(false);versionRow.add(update);}
            identity.add(versionRow);hero.add(identity,BorderLayout.CENTER);
            content.add(hero,row);row.gridy++;row.insets=new Insets(28,0,12,0);content.add(heading(resources,15),row);
            JPanel resourceGrid=new JPanel(new GridLayout(0,2,12,12));resourceGrid.setOpaque(false);
            AboutLinkIcon.Kind[] icons={AboutLinkIcon.Kind.WEBSITE,AboutLinkIcon.Kind.GITHUB,AboutLinkIcon.Kind.DOWNLOAD,AboutLinkIcon.Kind.ISSUES};
            for(int i=0;i<links.size();i++) {
                JButton button=(JButton)links.get(i).get();button.removeAll();button.setLayout(new BorderLayout(14,0));button.setText(null);
                JPanel text=vertical(5);text.add(heading(titles.get(i),14));text.add(secondary(descriptions.get(i),12));
                button.add(new JLabel(new AboutLinkIcon(icons[i%icons.length],24)),BorderLayout.WEST);button.add(text,BorderLayout.CENTER);
                // ResourceBorder 自绘轮廓，不支持 FlatButtonBorder 的样式字段。
                button.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, null);
                button.putClientProperty("jlshell.ideaForm", false);
                button.setBorder(new ResourceBorder());
                button.setPreferredSize(new Dimension(250,78));button.setMinimumSize(new Dimension(160,78));
                forward(text,button);resourceGrid.add(button);
            }
            row.gridy++;row.insets=new Insets(0,0,0,0);content.add(resourceGrid,row);
            row.gridy++;row.insets=new Insets(26,0,12,0);
            JPanel runtimeHeader=new JPanel(new BorderLayout());runtimeHeader.setOpaque(false);runtimeHeader.add(heading(runtime,15),BorderLayout.WEST);
            if(copy!=null)runtimeHeader.add(copy.get(),BorderLayout.EAST);content.add(runtimeHeader,row);
            Surface details=new Surface(false);details.setLayout(new GridBagLayout());details.setBorder(BorderFactory.createEmptyBorder(14,18,14,18));
            int line=0;for(List<String> entry:info)if(entry.size()>=2) {
                GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=line++;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(5,0,5,24);
                details.add(secondary(entry.getFirst(),12),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(5,0,5,0);
                JLabel value=new JLabel(entry.get(1));value.setFont(UIManager.getFont("defaultFont").deriveFont(12f));details.add(value,c);
            }
            row.gridy++;row.insets=new Insets(0,0,0,0);content.add(details,row);
            row.gridy++;row.insets=new Insets(20,0,0,0);content.add(secondary(footer,12),row);
            GridBagConstraints body=new GridBagConstraints();body.gridx=0;body.gridy=0;body.weightx=1;body.fill=GridBagConstraints.HORIZONTAL;body.anchor=GridBagConstraints.NORTH;
            page.add(content,body);body.gridy=1;body.weighty=1;page.add(Box.createVerticalGlue(),body);
            page.addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent event){
                ((GridLayout)resourceGrid.getLayout()).setColumns(page.getWidth()<620?1:2);resourceGrid.revalidate();
            }});
            JScrollPane scroll=new JScrollPane(new Tracking(page));scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(18);return scroll;
        };
    }
    private static void collect(Node node,List<Node> nodes) {
        if(node==null)return;nodes.add(node);
        if(node instanceof Button button)collect(button.getGraphic(),nodes);
        else if(node instanceof Parent parent)for(Node child:parent.getChildrenUnmodifiable())collect(child,nodes);
    }
    private static String label(List<Node> nodes,String style){return nodes.stream().filter(n->n instanceof Label&&n.getStyleClass().contains(style)).map(n->((Label)n).getText()).findFirst().orElse("");}
    private static JPanel vertical(int gap){
        JPanel p=new JPanel(){protected void addImpl(Component component,Object constraints,int index){
            if(getComponentCount()>0&&gap>0)super.addImpl(Box.createVerticalStrut(gap),null,-1);
            if(component instanceof JComponent child)child.setAlignmentX(Component.LEFT_ALIGNMENT);
            super.addImpl(component,constraints,index);
        }};p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setOpaque(false);return p;
    }
    private static JLabel heading(String text,int size){JLabel label=new JLabel(text);label.setFont(UIManager.getFont("defaultFont").deriveFont(Font.BOLD,(float)size));return label;}
    private static JLabel secondary(String text,int size){JLabel label=new JLabel(text);label.setFont(UIManager.getFont("defaultFont").deriveFont((float)size));label.setForeground(SwingTheme.color("secondary"));label.putClientProperty("JLShell.secondaryForeground",true);return label;}
    private static void forward(Component component,JButton target){component.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){target.doClick();}});if(component instanceof Container parent)for(Component child:parent.getComponents())forward(child,target);}
    private static final class Tracking extends JPanel implements Scrollable {
        Tracking(Component content){super(new BorderLayout());setOpaque(false);add(content,BorderLayout.NORTH);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return false;}
        public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 18;}public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(18,r.height-18);}
    }
    private static final class Surface extends JPanel {
        private final boolean hero;Surface(boolean hero){this.hero=hero;setOpaque(false);}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Color base=UIManager.getColor("Panel.background"),accent=UIManager.getColor("Component.focusColor");
            Color tint=new Color((base.getRed()*9+accent.getRed())/10,(base.getGreen()*9+accent.getGreen())/10,(base.getBlue()*9+accent.getBlue())/10);
            g.setPaint(hero?new GradientPaint(0,0,tint,getWidth(),getHeight(),base):base);
            var shape=new RoundRectangle2D.Double(.5,.5,getWidth()-1,getHeight()-1,18,18);g.fill(shape);g.setColor(SwingTheme.color("border"));g.draw(shape);
        }finally{g.dispose();}super.paintComponent(graphics);}
    }
    private static final class ResourceBorder extends javax.swing.border.AbstractBorder {
        public Insets getBorderInsets(Component c){return new Insets(16,16,16,16);}
        public Insets getBorderInsets(Component c,Insets insets){insets.set(16,16,16,16);return insets;}
        public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height){
            Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color("border"));g.draw(new RoundRectangle2D.Double(x+.5,y+.5,width-1,height-1,12,12));
            }finally{g.dispose();}
        }
    }
}
