package com.jlshell.ui.swing.idea;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatPopupMenuUI;
import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.*;

/** ComboBoxPopup/DarculaComboBoxUI geometry on the existing Swing keyboard/selection contract. */
public final class IdeaComboBoxUI extends FlatComboBoxUI {
    @Override protected ComboPopup createPopup() {
        return new FlatComboPopup(comboBox) {
            @Override protected void configurePopup() {
                super.configurePopup();
                setName("settings.comboPopup");setOpaque(false);setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
                list.setName("settings.comboList");list.setBackground(SwingTheme.color("popup"));
                list.setSelectionBackground(SwingTheme.color("popupSelection"));list.setSelectionForeground(UIManager.getColor("Label.foreground"));
                list.setCellRenderer(comboBox.getRenderer());
                scroller.setViewportBorder(null);scroller.setBorder(BorderFactory.createEmptyBorder());
                scroller.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                scroller.getVerticalScrollBar().setUnitIncrement(24);
                // A transparent owned window supplies rounded corners without requiring JetBrains Runtime
                // or FlatLaf's macOS native-corner path (disabled upstream on macOS 14.4+).
                setUI(new FlatPopupMenuUI(){
                    @Override public Popup getPopup(JPopupMenu menu,int x,int y){
                        JWindow window=new JWindow(SwingUtilities.getWindowAncestor(comboBox));
                        window.setName("settings.comboWindow");window.setType(Window.Type.POPUP);window.setFocusableWindowState(false);
                        boolean alpha=window.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
                        if(alpha)window.setBackground(new Color(0,true));
                        window.getRootPane().putClientProperty("Window.shadow",false);
                        window.setContentPane(new IdeaPopupSurface(menu,alpha));
                        window.pack();int margin=alpha?IdeaPopupSurface.SHADOW:0;
                        window.setLocation(x-margin-1,y-margin-1);
                        return new Popup() {
                            private boolean registered;
                            private final Runnable dismiss = () -> comboBox.setPopupVisible(false);
                            private final HierarchyListener hierarchy = event -> {
                                if (!comboBox.isShowing()) dismiss.run();
                            };
                            private final AWTEventListener outside = event -> {
                                if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED
                                        && mouse.getSource() instanceof Component target
                                        && !SwingUtilities.isDescendingFrom(target, window)
                                        && target != comboBox && !SwingUtilities.isDescendingFrom(target, comboBox)) {
                                    dismiss.run();
                                } else if (event instanceof WindowEvent change
                                        && change.getWindow() == window.getOwner()
                                        && (change.getID() == WindowEvent.WINDOW_DEACTIVATED
                                            || change.getID() == WindowEvent.WINDOW_CLOSING)) {
                                    dismiss.run();
                                }
                            };
                            private final ComponentListener movement = new ComponentAdapter() {
                                public void componentMoved(ComponentEvent event) { dismiss.run(); }
                                public void componentResized(ComponentEvent event) { dismiss.run(); }
                                public void componentHidden(ComponentEvent event) { dismiss.run(); }
                            };
                            public void show() {
                                registered = true;
                                comboBox.addHierarchyListener(hierarchy);
                                window.getOwner().addComponentListener(movement);
                                Toolkit.getDefaultToolkit().addAWTEventListener(outside, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
                                window.setVisible(true);
                            }
                            public void hide() {
                                if (registered) {
                                    registered = false;
                                    comboBox.removeHierarchyListener(hierarchy);
                                    window.getOwner().removeComponentListener(movement);
                                    Toolkit.getDefaultToolkit().removeAWTEventListener(outside);
                                }
                                window.dispose();
                            }
                        };
                    }
                });
            }
        };
    }
}
