package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Dedicated product presentation, shared by Settings and Help/About. No FX rendering. */
public final class SwingAboutPage {
    private SwingAboutPage() {}

    public static JComponent create(String applicationVersion, I18nService i18n, Supplier<String> diagnostics, Runnable checkUpdates) {
        String description = i18n.get("preferences.about.description");
        String version = i18n.get("preferences.about.version", applicationVersion);
        List<String> titles = List.of(i18n.get("preferences.about.website"), i18n.get("preferences.about.github"),
                i18n.get("preferences.about.releases"), i18n.get("preferences.about.feedback"));
        List<String> descriptions = List.of(i18n.get("preferences.about.website.description"), i18n.get("preferences.about.github.description"),
                i18n.get("preferences.about.releases.description"), i18n.get("preferences.about.feedback.description"));
        List<String> addresses = List.of("https://jlshell.oomn.net", "https://github.com/Voghost/JLShell", "https://github.com/Voghost/JLShell/releases/latest", "https://github.com/Voghost/JLShell/issues");
        List<Supplier<JComponent>> links = new ArrayList<>();
        for (String address : addresses) links.add(() -> {
            JButton button = new JButton(); button.setLayout(new BorderLayout(12, 0));
            button.addActionListener(event -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI.create(address)); }
                catch (Exception error) { org.slf4j.LoggerFactory.getLogger(SwingAboutPage.class).warn("Cannot open product link", error); }
            }));
            return button;
        });
        List<List<String>> info = List.of(
                List.of(i18n.get("preferences.about.author"), "Voghost"), List.of(i18n.get("preferences.about.license"), "MIT"),
                List.of(i18n.get("preferences.about.repository"), "Voghost/JLShell"), List.of(i18n.get("preferences.about.runtime.app"), applicationVersion),
                List.of(i18n.get("preferences.about.runtime.java"), System.getProperty("java.runtime.version", System.getProperty("java.version", "-"))),
                List.of(i18n.get("preferences.about.runtime.system"), System.getProperty("os.name", "-") + " " + System.getProperty("os.version", "") + " · " + System.getProperty("os.arch", "-")));
        Supplier<JComponent> copy = () -> {
            JButton button = new JButton(i18n.get("preferences.about.runtime.copy")); button.setName("about.copyRuntime");
            button.addActionListener(event -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(diagnostics.get()), null);
                button.setText(i18n.get("preferences.about.runtime.copied")); button.setEnabled(false);
                Timer reset = new Timer(1400, ignored -> { button.setText(i18n.get("preferences.about.runtime.copy")); button.setEnabled(true); });
                reset.setRepeats(false); reset.start();
            });
            return button;
        };
        Supplier<JComponent> updates = checkUpdates == null ? null : () -> {
            JButton button = new JButton(i18n.get("menu.help.checkUpdates")); button.addActionListener(event -> checkUpdates.run()); return button;
        };
        String resources=i18n.get("preferences.about.resources"),runtime=i18n.get("preferences.about.details"),footer=i18n.get("preferences.about.footer");
            JPanel page=new JPanel(new GridBagLayout());page.setName("about.page");page.setOpaque(false);page.setBorder(BorderFactory.createEmptyBorder(12,24,12,24));
            JPanel content=new JPanel(new GridBagLayout());content.setOpaque(false);
            GridBagConstraints row=new GridBagConstraints();row.gridx=0;row.gridy=0;row.weightx=1;row.fill=GridBagConstraints.HORIZONTAL;row.anchor=GridBagConstraints.NORTHWEST;
            Surface hero=new Surface(true);hero.setLayout(new BorderLayout(20,0));hero.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
            JLabel mark=new JLabel(new SwingBrandIcon(72));hero.add(mark,BorderLayout.WEST);
            JPanel identity=vertical(6);identity.add(heading("JLShell",32));identity.add(secondary(description,14));
            JPanel versionRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));versionRow.setOpaque(false);versionRow.add(secondary(version,12));
            if(updates!=null){versionRow.add(Box.createHorizontalStrut(16));JButton update=(JButton)updates.get();update.removeAll();update.setLayout(null);update.setIcon(new WorkbenchIcon(WorkbenchIcon.Kind.REFRESH));update.setContentAreaFilled(false);update.setBorderPainted(false);versionRow.add(update);}
            identity.add(versionRow);hero.add(identity,BorderLayout.CENTER);
            row.weighty=.25;row.fill=GridBagConstraints.BOTH;
            content.add(hero,row);row.gridy++;row.weighty=0;row.fill=GridBagConstraints.HORIZONTAL;row.insets=new Insets(12,0,8,0);content.add(heading(resources,15),row);
            JPanel resourceGrid=new JPanel(new GridLayout(0,2,8,8));resourceGrid.setName("about.resources");resourceGrid.setOpaque(false);
            AboutLinkIcon.Kind[] icons={AboutLinkIcon.Kind.WEBSITE,AboutLinkIcon.Kind.GITHUB,AboutLinkIcon.Kind.DOWNLOAD,AboutLinkIcon.Kind.ISSUES};
            for(int i=0;i<links.size();i++) {
                JButton button=(JButton)links.get(i).get();button.removeAll();button.setLayout(new BorderLayout(14,0));button.setText(null);
                JPanel text=vertical(5);text.add(heading(titles.get(i),14));text.add(secondary(descriptions.get(i),12));
                button.add(new JLabel(new AboutLinkIcon(icons[i%icons.length],24)),BorderLayout.WEST);button.add(text,BorderLayout.CENTER);
                // ResourceBorder 自绘轮廓，不支持 FlatButtonBorder 的样式字段。
                button.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, null);
                button.putClientProperty("jlshell.ideaForm", false);
                button.setBorder(new ResourceBorder());
                button.setPreferredSize(new Dimension(250,64));button.setMinimumSize(new Dimension(160,64));
                forward(text,button);resourceGrid.add(button);
            }
            row.gridy++;row.insets=new Insets(0,0,0,0);row.weighty=.5;row.fill=GridBagConstraints.BOTH;content.add(resourceGrid,row);
            row.gridy++;row.weighty=0;row.fill=GridBagConstraints.HORIZONTAL;row.insets=new Insets(12,0,8,0);
            JPanel runtimeHeader=new JPanel(new BorderLayout());runtimeHeader.setOpaque(false);runtimeHeader.add(heading(runtime,15),BorderLayout.WEST);
            if(copy!=null)runtimeHeader.add(copy.get(),BorderLayout.EAST);content.add(runtimeHeader,row);
            Surface details=new Surface(false);details.setName("about.details");details.setLayout(new GridBagLayout());details.setBorder(BorderFactory.createEmptyBorder(10,16,10,16));
            int line=0;for(List<String> entry:info)if(entry.size()>=2) {
                GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=line++;c.anchor=GridBagConstraints.WEST;c.insets=new Insets(1,0,1,24);
                details.add(secondary(entry.getFirst(),12),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(1,0,1,0);
                JLabel value=new JLabel(entry.get(1));value.setFont(UIManager.getFont("defaultFont").deriveFont(12f));details.add(value,c);
            }
            row.gridy++;row.insets=new Insets(0,0,0,0);row.weighty=.25;row.fill=GridBagConstraints.BOTH;content.add(details,row);
            row.gridy++;row.weighty=0;row.fill=GridBagConstraints.HORIZONTAL;row.insets=new Insets(12,0,0,0);JLabel footerLabel=secondary(footer,12);footerLabel.setName("about.footer");content.add(footerLabel,row);
            GridBagConstraints body=new GridBagConstraints();body.gridx=0;body.gridy=0;body.weightx=1;body.weighty=1;body.fill=GridBagConstraints.BOTH;body.anchor=GridBagConstraints.NORTH;
            page.add(content,body);
            page.addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent event){
                ((GridLayout)resourceGrid.getLayout()).setColumns(page.getWidth()<480?1:2);resourceGrid.revalidate();
            }});
            JScrollPane scroll=new JScrollPane(new Tracking(page));scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getVerticalScrollBar().setUnitIncrement(18);return scroll;
    }
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
        Tracking(Component content){super(new BorderLayout());setOpaque(false);add(content,BorderLayout.CENTER);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return getParent() instanceof JViewport viewport && viewport.getHeight() >= getPreferredSize().height;}
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
        public Insets getBorderInsets(Component c){return new Insets(10,14,10,14);}
        public Insets getBorderInsets(Component c,Insets insets){insets.set(10,14,10,14);return insets;}
        public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height){
            Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color("border"));g.draw(new RoundRectangle2D.Double(x+.5,y+.5,width-1,height-1,12,12));
            }finally{g.dispose();}
        }
    }
}
