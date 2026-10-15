package com.jlshell.app;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

final class NativeSettingsProbe {
    private NativeSettingsProbe() {}
    static <T> List<T> all(Component root,Class<T> type) {
        List<T> result=new ArrayList<>();
        if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof Container parent)for(Component child:parent.getComponents())result.addAll(all(child,type));
        return result;
    }
    static JComponent styled(Component root,String style) {
        return all(root,JComponent.class).stream().filter(Component::isShowing).filter(component->component.getClientProperty("jlshell.styleClasses") instanceof List<?> values&&values.contains(style)).findFirst().orElse(null);
    }
    static JButton button(Component root,String text) {
        return all(root,JButton.class).stream().filter(button->text.equals(button.getText())).findFirst().orElseThrow(()->new AssertionError("Missing native button: "+text));
    }
    static void assertNative(Component root) {
        org.junit.jupiter.api.Assertions.assertTrue(all(root,javafx.embed.swing.JFXPanel.class).isEmpty(),"Settings must not contain any JFXPanel");
    }
}
