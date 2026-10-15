package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

/** Native key capture, independent of an FX Scene. The callback owns conflict checking and storage. */
public final class SwingShortcutRecording {
    public static final String KEY = "jlshell.shortcutRecording";
    public record Binding(String prompt, Function<String,String> commit) {}
    private SwingShortcutRecording() {}

    static Runnable install(JButton button, Binding binding) {
        var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
        java.awt.KeyEventDispatcher[] active=new java.awt.KeyEventDispatcher[1];
        String[] original={null};
        Runnable cancel=()->{
            if(active[0]!=null)manager.removeKeyEventDispatcher(active[0]);
            active[0]=null;
            if(original[0]!=null)button.setText(original[0]);
            button.putClientProperty("jlshell.recording",false);
        };
        button.addActionListener(action->{
            cancel.run(); original[0]=button.getText();button.setText(binding.prompt());
            button.putClientProperty("jlshell.recording",true);button.requestFocusInWindow();
            active[0]=event->{
                if(!button.isShowing()){cancel.run();return false;}
                if(event.getID()!=KeyEvent.KEY_PRESSED)return true;
                if(event.getKeyCode()==KeyEvent.VK_ESCAPE){cancel.run();return true;}
                String spec=spec(event);if(spec==null)return true;
                manager.removeKeyEventDispatcher(active[0]);active[0]=null;
                FxThread.run(()->{
                    String error=binding.commit().apply(spec);
                    SwingUtilities.invokeLater(()->{
                        button.putClientProperty("jlshell.recording",false);
                        if(error!=null){button.setText(original[0]);button.setToolTipText(error);}
                        original[0]=null;
                    });
                });
                return true;
            };
            manager.addKeyEventDispatcher(active[0]);
        });
        button.addFocusListener(new java.awt.event.FocusAdapter(){public void focusLost(java.awt.event.FocusEvent e){if(active[0]!=null)cancel.run();}});
        return cancel;
    }

    static String spec(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.VK_SHIFT||event.getKeyCode()==KeyEvent.VK_CONTROL||event.getKeyCode()==KeyEvent.VK_ALT||event.getKeyCode()==KeyEvent.VK_META)return null;
        var parts=new ArrayList<String>();
        if(event.isControlDown())parts.add("ctrl");if(event.isAltDown())parts.add("alt");
        if(event.isShiftDown())parts.add("shift");if(event.isMetaDown())parts.add("meta");
        String name=null;
        for(var field:KeyEvent.class.getFields())if(field.getName().startsWith("VK_")) {
            try {if(field.getInt(null)==event.getKeyCode()){name=field.getName().substring(3);break;}}
            catch(IllegalAccessException ignored) {}
        }
        if(name==null)return null;parts.add(name);return String.join(" ",parts);
    }
}
