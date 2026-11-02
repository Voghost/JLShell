package com.jlshell.ui.swing;

import com.jlshell.ui.swing.idea.IdeaContentPopup;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.event.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke",matches="true")
class IdeaContentPopupTest {
    @Test void escapeAndOutsideClickCloseWithoutMinimizingOwner() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK,"#3574f0",null,13);
            var frame=new JFrame();var anchor=new JButton("details");frame.add(anchor);frame.setSize(500,400);frame.setVisible(true);
            var popup=new IdeaContentPopup();
            try {
                popup.showAbove(anchor,new JPanel(),300);assertTrue(popup.isShowing());
                assertTrue(popup.dispatchKeyEvent(new KeyEvent(anchor,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_ESCAPE,(char)27)));
                assertFalse(popup.isShowing());assertEquals(JFrame.NORMAL,frame.getExtendedState());
                popup.showAbove(anchor,new JPanel(),300);assertTrue(popup.isShowing());
                popup.eventDispatched(new MouseEvent(anchor,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,0,0,-1000,-1000,1,false,MouseEvent.BUTTON1));
                assertFalse(popup.isShowing());assertEquals(JFrame.NORMAL,frame.getExtendedState());
                popup.close();popup.close();
            } finally {popup.close();frame.dispose();}
        }).get(10,TimeUnit.SECONDS);
    }
}
