package com.jlshell.ui.swing;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Text;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** IDEA-style font controls beside a live native terminal preview. */
final class SwingTerminalFontPanel {
    private SwingTerminalFontPanel() {}
    static Supplier<JComponent> prepare(SwingFormRenderer renderer,GridPane model) {
        record Field(int row,int column,Supplier<JComponent> view) {}
        List<Field> fields=new ArrayList<>();String title="";
        for(Node child:model.getChildren()) {
            int row=GridPane.getRowIndex(child)==null?0:GridPane.getRowIndex(child);
            int column=GridPane.getColumnIndex(child)==null?0:GridPane.getColumnIndex(child);
            if(child instanceof Text)continue;
            if("settings-terminal-preview-caption".equals(child.getId())){title=((Label)child).getText();continue;}
            fields.add(new Field(row,column,renderer.prepare(child)));
        }
        AtomicReference<TerminalFontPreview> view=new AtomicReference<>();
        @SuppressWarnings("unchecked")
        var profileModel=(javafx.beans.value.ObservableValue<com.jlshell.core.model.FontProfile>)model.getProperties().get("jlshell.terminalPreviewProfile");
        var profile=renderer.observe(profileModel,next->{if(view.get()!=null)view.get().setProfile(next);});
        String previewTitle=title;
        return ()->{
            JPanel controls=new JPanel(new GridBagLayout());controls.setOpaque(false);
            for(Field field:fields) {
                GridBagConstraints c=new GridBagConstraints();c.gridx=field.column();c.gridy=field.row();c.anchor=GridBagConstraints.WEST;
                c.insets=new Insets(4,0,4,field.column()==0?12:0);c.fill=field.column()==0?GridBagConstraints.NONE:GridBagConstraints.HORIZONTAL;
                controls.add(field.view().get(),c);
            }
            GridBagConstraints glue=new GridBagConstraints();glue.gridx=2;glue.weightx=1;glue.gridy=0;controls.add(Box.createHorizontalGlue(),glue);
            GridBagConstraints bottom=new GridBagConstraints();bottom.gridy=100;bottom.weighty=1;controls.add(Box.createVerticalGlue(),bottom);
            JPanel previewPanel=new JPanel(new BorderLayout(0,12));previewPanel.setOpaque(false);previewPanel.setBorder(BorderFactory.createCompoundBorder(
                    SwingTheme.border(0,1,0,0),BorderFactory.createEmptyBorder(4,20,4,4)));
            JLabel caption=new JLabel(previewTitle);caption.putClientProperty("JLShell.secondaryForeground",true);caption.setForeground(SwingTheme.color("secondary"));previewPanel.add(caption,BorderLayout.NORTH);
            TerminalFontPreview text=new TerminalFontPreview(profile.get());view.set(text);
            JScrollPane previewScroll=new JScrollPane(text);previewScroll.setBorder(BorderFactory.createEmptyBorder());
            previewScroll.setOpaque(false);previewScroll.getViewport().setOpaque(false);previewPanel.add(previewScroll);
            JPanel panel=new JPanel(new GridLayout(1,2,20,0));panel.setOpaque(false);panel.add(controls);panel.add(previewPanel);panel.setPreferredSize(new Dimension(680,320));
            panel.addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent event){
                boolean compact=panel.getWidth()<640;((GridLayout)panel.getLayout()).setColumns(compact?1:2);panel.setPreferredSize(new Dimension(680,compact?640:320));panel.revalidate();
            }});return panel;
        };
    }
}
