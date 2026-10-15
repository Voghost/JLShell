package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import java.awt.*;
import java.util.Set;
import java.util.function.Function;

/** FontInfoRendererBuilder conventions: preview only in popup, font-type groups, inset selection. */
public final class IdeaComboBoxRenderer implements ListCellRenderer<Object> {
    private static final Set<String> FAMILIES=Set.copyOf(java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    // Legacy symbol fonts remap ASCII to pictograms despite canDisplayUpTo reporting success.
    private static final Set<String> SYMBOL_FAMILIES=Set.of("symbol","webdings","wingdings","wingdings 2","wingdings 3","zapf dingbats","dingbats","apple symbols");
    private final Function<Object,String> display;
    private final boolean fonts,groups;
    private final Set<String> monospaced;
    private final String monoTitle,proportionalTitle;
    public IdeaComboBoxRenderer(Function<Object,String> display,boolean fonts,boolean groups,Set<String> monospaced,String monoTitle,String proportionalTitle){
        this.display=display;this.fonts=fonts;this.groups=groups;this.monospaced=monospaced;this.monoTitle=monoTitle;this.proportionalTitle=proportionalTitle;
    }
    public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
        String text=display.apply(value);Font base=UIManager.getFont("defaultFont");
        JLabel label=new JLabel(text);label.putClientProperty("html.disable",true);label.setFont(base);
        label.setForeground(UIManager.getColor("Label.foreground"));
        if(fonts&&index>=0&&FAMILIES.contains(text)&&!SYMBOL_FAMILIES.contains(text.toLowerCase(java.util.Locale.ROOT))){Font preview=new Font(text,Font.PLAIN,base.getSize());if(preview.canDisplayUpTo(text)==-1)label.setFont(preview);}
        if(value instanceof com.jlshell.ui.theme.AccentColor accent)label.setIcon(new Icon(){
            public int getIconWidth(){return 14;}public int getIconHeight(){return 14;}
            public void paintIcon(Component c,Graphics g,int x,int y){g.setColor(Color.decode(accent.color()));g.fillOval(x+1,y+1,12,12);}
        });
        if(index<0){label.setBorder(BorderFactory.createEmptyBorder());return label;}
        label.setBorder(BorderFactory.createEmptyBorder(2,8,2,8));
        SelectablePanel item=SelectablePanel.wrap(label,SwingTheme.color("popup"));
        item.selectionArc=6;item.selectionInsets=new Insets(0,2,0,2);item.selectionColor=selected?SwingTheme.color("popupSelection"):null;
        item.preferredHeight=Math.max(24,list.getFontMetrics(base).getHeight()+6);
        // Like FontInfoRendererBuilder.rowWidth, do not measure hundreds of font families for popup width.
        if(fonts)item.preferredWidth=160;
        String group=null;
        if(fonts&&groups){
            boolean mono=monospaced.contains(text);
            if(mono&&(index==0||!monospaced.contains(String.valueOf(list.getModel().getElementAt(index-1)))))group=monoTitle;
            else if(!mono&&index>0&&monospaced.contains(String.valueOf(list.getModel().getElementAt(index-1))))group=proportionalTitle;
        }
        if(group==null)return item;
        JPanel wrapper=new JPanel(new BorderLayout());wrapper.putClientProperty("jlshell.fontGroup",group);wrapper.setBackground(SwingTheme.color("popup"));
        wrapper.add(new IdeaGroupHeaderSeparator(group,index<=1,base),BorderLayout.NORTH);wrapper.add(item);
        return wrapper;
    }
}
