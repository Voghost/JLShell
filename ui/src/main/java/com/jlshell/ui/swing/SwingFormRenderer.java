package com.jlshell.ui.swing;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.VaultEntryProfile;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Native Swing presentation of the existing form models. FX controls retain validation and
 * service callbacks, but no Scene, skin or JFXPanel is created for these dialogs.
 * Prepare/dispose on FX; construct and mutate Swing components only on EDT.
 */
final class SwingFormRenderer implements AutoCloseable {
    private final I18nService i18n;
    private final boolean settingsLayout;
    private final List<Runnable> removers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private final java.util.Map<String, Node> models = new java.util.HashMap<>();

    java.util.Map<String, Node> models() { return java.util.Map.copyOf(models); }

    SwingFormRenderer(I18nService i18n) { this(i18n, false); }
    SwingFormRenderer(I18nService i18n, boolean settingsLayout) { this.i18n = i18n; this.settingsLayout = settingsLayout; }

    <T> AtomicReference<T> observe(ObservableValue<T> source, Consumer<T> update) {
        AtomicReference<T> value = new AtomicReference<>(source.getValue());
        javafx.beans.value.ChangeListener<T> listener = (o, old, next) -> {
            value.set(next);
            SwingUtilities.invokeLater(() -> { if (!closed) update.accept(next); });
        };
        source.addListener(listener);
        removers.add(() -> source.removeListener(listener));
        return value;
    }

    void onDispose(Runnable remover) { removers.add(remover); }
    boolean isClosed() { return closed; }

    Supplier<JComponent> prepare(Node node) {
        if (node == null) return JPanel::new;
        if (node.getId() != null) models.put(node.getId(), node);
        AtomicReference<JComponent> view = new AtomicReference<>();
        var enabled = observe(node.disabledProperty(), value -> {
            if (view.get() != null) setEnabled(view.get(), !value);
        });
        var visible = observe(node.visibleProperty(), value -> {});
        var managed = observe(node.managedProperty(), value -> {});
        // Visibility callbacks capture only immutable values; they never read an FX Node on EDT.
        Runnable refresh = () -> { if (view.get() != null) {
            view.get().setVisible(visible.get() && managed.get());
            if (view.get().getParent() != null) { view.get().getParent().revalidate(); view.get().getParent().repaint(); }
        }};
        observe(node.visibleProperty(), value -> refresh.run());
        observe(node.managedProperty(), value -> refresh.run());
        AtomicReference<javafx.scene.layout.Background> background = node instanceof Region region
                ? observe(region.backgroundProperty(),next->{if(view.get()!=null&&next!=null&&!next.getFills().isEmpty()){
                    view.get().setOpaque(true);view.get().setBackground(awtColor(next.getFills().getFirst().getFill()));}}) : null;
        double preferredWidth = node instanceof Region region ? region.getPrefWidth() : -1;
        String id = node.getId();
        List<String> styles = List.copyOf(node.getStyleClass());
        String icon = (String) node.getProperties().get("swing.icon");
        String workbenchIcon=(String)node.getProperties().get("swing.workbenchIcon");
        String aboutIcon=(String)node.getProperties().get("swing.aboutIcon");
        Supplier<JComponent> factory;
        if (node instanceof javafx.scene.text.Text text) factory = previewText(text);
        else if (node instanceof Spinner<?> spinner) factory = spinner(spinner);
        else if (node instanceof TextInputControl input) factory = text(input);
        else if (node instanceof ComboBox<?> box) factory = combo(box);
        else if (node instanceof ListView<?> list) factory = settingsLayout ? SwingSettingsWidgets.list(this,list) : list(list);
        else if (node instanceof TableView<?> table) factory = SwingSettingsWidgets.table(this,table);
        else if (node instanceof TreeView<?> tree) factory = SwingSettingsWidgets.tree(this,tree);
        else if (node instanceof SplitPane split) factory = split(split);
        else if (node instanceof Slider slider) factory = slider(slider);
        else if (node instanceof ColorPicker picker) factory = colorPicker(picker);
        else if (node instanceof ProgressIndicator progress) factory = progress(progress);
        else if (node instanceof javafx.scene.shape.Rectangle rectangle) factory = rectangle(rectangle);
        else if (node instanceof javafx.scene.image.ImageView image) factory = image(image);
        else if (node instanceof CheckBox check) factory = check(check);
        else if (node instanceof ButtonBase button) factory = button(button);
        else if (node instanceof Label label) factory = label(label);
        else if (node instanceof Separator) factory = JSeparator::new;
        else if (node instanceof TabPane tabs) factory = tabs(tabs);
        else if(node instanceof ScrollPane scroll&&scroll.getStyleClass().contains("about-scroll-pane"))factory=SwingAboutPage.prepare(this,scroll,i18n);
        else if (node instanceof ScrollPane scroll) {
            var child = prepare(scroll.getContent());
            factory = () -> {
                JScrollPane pane = new JScrollPane(settingsLayout ? new WidthTrackingPanel(child.get()) : child.get());
                pane.setBorder(BorderFactory.createEmptyBorder());
                pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                pane.getVerticalScrollBar().setUnitIncrement(16);
                return pane;
            };
        } else if(node instanceof StackPane avatar&&styles.contains("plugin-avatar")) {
            String initial=((Label)avatar.getChildren().getFirst()).getText();
            factory=()->new JLabel(initial,SwingConstants.CENTER) {
                {setPreferredSize(new Dimension(40,40));setMinimumSize(new Dimension(40,40));}
                protected void paintComponent(java.awt.Graphics graphics){
                    var g=(java.awt.Graphics2D)graphics.create();try{g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(UIManager.getColor("Component.focusColor"));g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);
                    }finally{g.dispose();}super.paintComponent(graphics);
                }
            };
        }
        else if(node instanceof GridPane grid&&grid.getStyleClass().contains("terminal-font-layout"))factory=SwingTerminalFontPanel.prepare(this,grid);
        else if (node instanceof BorderPane border) factory = border(border);
        else if (node instanceof Pane pane) factory = pane(pane);
        else if(aboutIcon!=null)factory=()->new JLabel(new AboutLinkIcon(AboutLinkIcon.Kind.valueOf(aboutIcon),18));
        else if(workbenchIcon!=null)factory=()->new JLabel(new WorkbenchIcon(WorkbenchIcon.Kind.valueOf(workbenchIcon)));
        else if (node instanceof Region) factory = JPanel::new;
        else throw new IllegalArgumentException("Unsupported native form node: " + node.getClass().getName());
        AtomicReference<Supplier<JComponent>> currentFactory=new AtomicReference<>(factory);
        if (node instanceof Pane pane) {
            javafx.collections.ListChangeListener<Node> listener = change -> {
                Supplier<JComponent> replacement = pane instanceof BorderPane border ? border(border) : pane(pane);
                currentFactory.set(replacement);
                SwingUtilities.invokeLater(() -> {
                    if (closed || !(view.get() instanceof JPanel panel)) return;
                    JPanel next = (JPanel)replacement.get();
                    panel.removeAll(); panel.setLayout(next.getLayout());
                    for (Component child : next.getComponents()) {
                        Object constraints = next.getLayout() instanceof GridBagLayout layout ? layout.getConstraints(child)
                                : next.getLayout() instanceof java.awt.BorderLayout layout ? layout.getConstraints(child) : null;
                        panel.add(child,constraints);
                    }
                    panel.revalidate(); panel.repaint();
                });
            };
            pane.getChildren().addListener(listener);
            removers.add(() -> pane.getChildren().removeListener(listener));
        }
        return () -> {
            JComponent component = currentFactory.get().get();
            view.set(component);
            component.setName(id);
            component.putClientProperty("jlshell.styleClasses",styles);
            if(background!=null&&background.get()!=null&&!background.get().getFills().isEmpty()) {
                component.setOpaque(true);component.setBackground(awtColor(background.get().getFills().getFirst().getFill()));
            }
            if (!(node instanceof javafx.scene.text.Text)) component.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
            component.putClientProperty("jlshell.formModel", node);
            IdeaFormStyle.apply(component);
            component.setEnabled(!enabled.get());
            component.setVisible(visible.get() && managed.get());
            if (icon != null && component instanceof JLabel label) {
                label.setIcon(UIManager.getIcon(icon)); label.setVerticalAlignment(SwingConstants.TOP);
            }
            if (styles.contains("connection-subtitle") || styles.contains("connection-field-label")
                    || styles.contains("connection-tab-description") || styles.contains("connection-identity-label")
                    || styles.contains("settings-card-description")) {
                component.putClientProperty("JLShell.secondaryForeground", true);
                component.setForeground(SwingTheme.color("secondary"));
            }
            if(settingsLayout&&(styles.contains("settings-page")||styles.contains("about-page")))component.setBorder(BorderFactory.createEmptyBorder(8,16,12,16));
            if (settingsLayout && styles.contains("settings-card-grid")) component.setBorder(BorderFactory.createEmptyBorder(4, 16, 20, 20));
            if (settingsLayout && styles.contains("settings-preview-box")) component.setBorder(BorderFactory.createCompoundBorder(
                    SwingTheme.border(1,1,1,1), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            if (settingsLayout && preferredWidth > 0
                    && (component instanceof JComboBox<?> || component instanceof JTextField || component instanceof JSpinner)) {
                if (component instanceof JTextField field) field.setColumns(0);
                component.setPreferredSize(new Dimension((int)preferredWidth, component.getPreferredSize().height));
                component.setMinimumSize(new Dimension(Math.min(80,(int)preferredWidth), component.getPreferredSize().height));
            }
            if(styles.contains("shortcut-key-button")||styles.contains("shortcut-clear-button")) {
                component.setForeground(UIManager.getColor("Label.foreground"));
                component.setOpaque(false);
                ((JButton)component).setContentAreaFilled(false);((JButton)component).setBorderPainted(false);
            }
            if(styles.contains("shortcut-clear-button"))component.setPreferredSize(new Dimension(24,24));
            if(styles.contains("plugin-avatar")){component.setForeground(java.awt.Color.WHITE);component.setFont(component.getFont().deriveFont(Font.BOLD,18f));}
            if(styles.contains("plugin-detail-pane"))component.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
            if(styles.contains("plugin-detail-title"))component.setFont(component.getFont().deriveFont(Font.BOLD,20f));
            if(styles.contains("plugin-item-name")||styles.contains("settings-card-title"))component.setFont(component.getFont().deriveFont(Font.BOLD));
            if(styles.contains("plugin-item-meta")||styles.contains("plugin-item-description")||styles.contains("plugin-detail-author")) {
                component.setFont(component.getFont().deriveFont(12f));component.setForeground(SwingTheme.color("secondary"));component.putClientProperty("JLShell.secondaryForeground",true);
            }
            if (styles.contains("message-heading")) component.setFont(component.getFont().deriveFont(Font.BOLD));
            if (styles.contains("connection-identity-header")) component.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
            if (styles.contains("connection-test-footer")) component.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
            return component;
        };
    }

    private Supplier<JComponent> text(TextInputControl source) {
        AtomicReference<JTextComponent> view = new AtomicReference<>();
        AtomicBoolean fromSwing = new AtomicBoolean();
        AtomicReference<String> value = new AtomicReference<>(source.getText());
        javafx.beans.value.ChangeListener<String> listener = (o, old, next) -> {
            value.set(next);
            if (!fromSwing.get()) SwingUtilities.invokeLater(() -> {
                JTextComponent target = view.get();
                if (!closed && target != null && !java.util.Objects.equals(target.getText(), next)) {
                    target.putClientProperty("jlshell.updating", true);
                    try { target.setText(next); } finally { target.putClientProperty("jlshell.updating", false); }
                }
            });
        };
        source.textProperty().addListener(listener);
        removers.add(() -> source.textProperty().removeListener(listener));
        boolean password = source instanceof PasswordField;
        boolean area = source instanceof TextArea;
        boolean editable = source.isEditable();
        int rows = area ? Math.max(3, ((TextArea) source).getPrefRowCount()) : 1;
        String prompt = source.getPromptText();
        String id = source.getId();
        int columns = "connection-port".equals(source.getId()) ? 5 : 20;
        return () -> {
            JTextComponent field = area ? new JTextArea(rows, 30) : password ? new JPasswordField(columns) : new JTextField(columns);
            field.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
            field.setEditable(editable); field.setText(value.get());
            IdeaFormStyle.apply(field);
            field.putClientProperty("JTextField.placeholderText", prompt);
            view.set(field);
            field.getDocument().addDocumentListener(new DocumentListener() {
                private void changed() {
                    if (Boolean.TRUE.equals(field.getClientProperty("jlshell.updating"))) return;
                    String text = field.getText();
                    FxThread.run(() -> { if (!closed) {
                        fromSwing.set(true);
                        try { source.setText(text); } finally { fromSwing.set(false); }
                    }});
                }
                public void insertUpdate(DocumentEvent e) { changed(); }
                public void removeUpdate(DocumentEvent e) { changed(); }
                public void changedUpdate(DocumentEvent e) { changed(); }
            });
            if (field instanceof JTextArea textArea) {
                textArea.setLineWrap(true); textArea.setWrapStyleWord(true);
                JScrollPane scroll = new JScrollPane(field); scroll.setName(id);
                field.setName(id); return scroll;
            }
            return field;
        };
    }

    private Supplier<JComponent> border(BorderPane source) {
        List<Node> nodes = java.util.Arrays.asList(source.getTop(),source.getLeft(),source.getCenter(),source.getRight(),source.getBottom());
        List<Supplier<JComponent>> children = nodes.stream().map(this::prepare).toList();
        var padding=source.getPadding();
        return () -> {
            JPanel panel=new JPanel(new java.awt.BorderLayout(8,8));panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder((int)padding.getTop(),(int)padding.getLeft(),(int)padding.getBottom(),(int)padding.getRight()));
            String[] positions={java.awt.BorderLayout.NORTH,java.awt.BorderLayout.WEST,java.awt.BorderLayout.CENTER,java.awt.BorderLayout.EAST,java.awt.BorderLayout.SOUTH};
            for(int i=0;i<nodes.size();i++)if(nodes.get(i)!=null)panel.add(children.get(i).get(),positions[i]);
            return panel;
        };
    }

    private Supplier<JComponent> split(SplitPane source) {
        var children=source.getItems().stream().map(this::prepare).toList();
        boolean horizontal=source.getOrientation()==javafx.geometry.Orientation.HORIZONTAL;
        double ratio=source.getStyleClass().contains("plugin-content-split")?.38:source.getDividerPositions().length>0?source.getDividerPositions()[0]:.5;
        return () -> {
            JComponent result=children.isEmpty()?new JPanel():children.getLast().get();
            for(int i=children.size()-2;i>=0;i--) {
                JSplitPane split=new ResizeSplitPane(horizontal?JSplitPane.HORIZONTAL_SPLIT:JSplitPane.VERTICAL_SPLIT,children.get(i).get(),result);
                split.setBorder(BorderFactory.createEmptyBorder());split.setDividerSize(4);split.setResizeWeight(ratio);
                SwingUtilities.invokeLater(()->split.setDividerLocation(ratio));result=split;
            }
            result.setPreferredSize(new Dimension(620,350));return result;
        };
    }

    private Supplier<JComponent> slider(Slider source) {
        AtomicReference<JSlider> view=new AtomicReference<>();
        double min=source.getMin(),max=source.getMax();
        var value=observe(source.valueProperty(),next->{if(view.get()!=null)view.get().setValue((int)Math.round((next.doubleValue()-min)/(max-min)*1000));});
        return ()->{JSlider slider=new JSlider(0,1000,(int)Math.round((value.get().doubleValue()-min)/(max-min)*1000));view.set(slider);
            slider.setPreferredSize(new Dimension(180,26));slider.addChangeListener(event->{double next=min+slider.getValue()/1000.0*(max-min);FxThread.run(()->{if(!closed&&Math.abs(source.getValue()-next)>.00001)source.setValue(next);});});return slider;};
    }

    private Supplier<JComponent> colorPicker(ColorPicker source) {
        AtomicReference<JButton> view=new AtomicReference<>();
        var value=observe(source.valueProperty(),next->{if(view.get()!=null)view.get().setIcon(colorIcon(awtColor(next)));});
        return ()->{JButton button=new JButton();view.set(button);button.setPreferredSize(new Dimension(72,26));button.setIcon(colorIcon(awtColor(value.get())));
            button.addActionListener(event->{java.awt.Color picked=JColorChooser.showDialog(button,"Color",awtColor(value.get()));
                if(picked!=null)FxThread.run(()->source.setValue(javafx.scene.paint.Color.rgb(picked.getRed(),picked.getGreen(),picked.getBlue())));});return button;};
    }

    private static Icon colorIcon(java.awt.Color color) {
        return new Icon() {
            public int getIconWidth(){return 38;} public int getIconHeight(){return 14;}
            public void paintIcon(Component c,java.awt.Graphics g,int x,int y){g.setColor(color);g.fillRoundRect(x,y,38,14,4,4);}
        };
    }

    private Supplier<JComponent> progress(ProgressIndicator source) {
        AtomicReference<JProgressBar> view=new AtomicReference<>();
        var value=observe(source.progressProperty(),next->{if(view.get()!=null){view.get().setIndeterminate(next.doubleValue()<0);view.get().setValue((int)(next.doubleValue()*100));}});
        return ()->{JProgressBar bar=new JProgressBar(0,100);view.set(bar);bar.setIndeterminate(value.get().doubleValue()<0);bar.setValue((int)(value.get().doubleValue()*100));bar.setPreferredSize(new Dimension(120,8));return bar;};
    }

    private Supplier<JComponent> rectangle(javafx.scene.shape.Rectangle source) {
        AtomicReference<JPanel> view=new AtomicReference<>();
        var fill=observe(source.fillProperty(),next->{if(view.get()!=null)view.get().setBackground(awtColor(next));});
        int width=(int)source.getWidth(),height=(int)source.getHeight();
        return ()->{JPanel panel=new JPanel();view.set(panel);panel.setBackground(awtColor(fill.get()));panel.setPreferredSize(new Dimension(width,height));return panel;};
    }

    private Supplier<JComponent> image(javafx.scene.image.ImageView source) {
        var image=source.getImage();
        java.awt.image.BufferedImage bitmap=null;
        if(image!=null&&image.getPixelReader()!=null) {
            int width=(int)image.getWidth(),height=(int)image.getHeight();
            bitmap=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels=new int[width*height];image.getPixelReader().getPixels(0,0,width,height,javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,width);
            bitmap.setRGB(0,0,width,height,pixels,0,width);
        }
        var ready=bitmap;int width=(int)source.getFitWidth(),height=(int)source.getFitHeight();
        return ()->new JLabel(ready==null?null:new ImageIcon(width>0&&height>0?ready.getScaledInstance(width,height,java.awt.Image.SCALE_SMOOTH):ready));
    }

    static java.awt.Color awtColor(Object paint) {
        if(paint instanceof javafx.scene.paint.Color color)return new java.awt.Color((float)color.getRed(),(float)color.getGreen(),(float)color.getBlue(),(float)color.getOpacity());
        return UIManager.getColor("Label.foreground");
    }

    private Supplier<JComponent> previewText(javafx.scene.text.Text source) {
        AtomicReference<JLabel> view = new AtomicReference<>();
        var text = observe(source.textProperty(), value -> { if (view.get() != null) view.get().setText(value); });
        var fill=observe(source.fillProperty(),value->{if(view.get()!=null)view.get().setForeground(awtColor(value));});
        var font = observe(source.fontProperty(), value -> {
            if (view.get() != null) view.get().setFont(new Font(value.getFamily(), Font.PLAIN, (int)value.getSize()));
        });
        return () -> { JLabel label = new JLabel(text.get()); view.set(label);
            label.setFont(new Font(font.get().getFamily(), Font.PLAIN, (int)font.get().getSize()));
            if(!javafx.scene.paint.Color.BLACK.equals(fill.get())||font.get().getFamily().equalsIgnoreCase("Monospaced"))label.setForeground(awtColor(fill.get())); return label; };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Supplier<JComponent> spinner(Spinner<?> source) {
        SpinnerValueFactory factory = source.getValueFactory();
        boolean integer = factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory;
        double minimum, maximum, step;
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory f) {
            minimum=f.getMin(); maximum=f.getMax(); step=f.getAmountToStepBy();
        } else if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f) {
            minimum=f.getMin(); maximum=f.getMax(); step=f.getAmountToStepBy();
        } else throw new IllegalArgumentException("Unsupported number spinner");
        AtomicReference<JSpinner> view = new AtomicReference<>();
        var value = observe(source.valueProperty(), next -> { if (view.get() != null) {
            view.get().putClientProperty("jlshell.updating",true); view.get().setValue(next);
            view.get().putClientProperty("jlshell.updating",false);
        }});
        return () -> {
            SpinnerNumberModel model = integer ? new SpinnerNumberModel(((Number)value.get()).intValue(),(int)minimum,(int)maximum,(int)step)
                    : new SpinnerNumberModel(((Number)value.get()).doubleValue(),minimum,maximum,step);
            JSpinner spinner = new JSpinner(model); view.set(spinner);
            spinner.setEditor(new JSpinner.NumberEditor(spinner,integer ? "0" : "0.0"));
            var editor = ((JSpinner.DefaultEditor)spinner.getEditor()).getTextField();
            ((javax.swing.text.DefaultFormatter)editor.getFormatter()).setCommitsOnValidEdit(true);
            spinner.addChangeListener(event -> { if (!Boolean.TRUE.equals(spinner.getClientProperty("jlshell.updating"))) {
                Number number=(Number)spinner.getValue();
                Object next=integer ? (Object)number.intValue() : number.doubleValue();
                FxThread.run(() -> { if (!closed) factory.setValue(next); });
            }});
            return spinner;
        };
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(JComponent content) { super(new java.awt.BorderLayout()); setOpaque(false); add(content, java.awt.BorderLayout.NORTH); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r,int o,int d) { return 16; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r,int o,int d) { return Math.max(16,r.height-16); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Supplier<JComponent> combo(ComboBox source) {
        AtomicReference<JComboBox<Object>> view = new AtomicReference<>();
        AtomicReference<List<Object>> entries = new AtomicReference<>(new ArrayList<>(source.getItems()));
        AtomicReference<Object> selected = observe(source.valueProperty(), next -> {
            if (view.get() != null) { view.get().putClientProperty("jlshell.updating", true);
                view.get().setSelectedItem(next); view.get().putClientProperty("jlshell.updating", false); }
        });
        javafx.collections.ListChangeListener<Object> listener = change -> {
            List<Object> copy = new ArrayList<>(source.getItems()); entries.set(copy);
            Object selection = source.getValue();
            SwingUtilities.invokeLater(() -> { if (!closed && view.get() != null) {
                JComboBox<Object> box = view.get(); box.putClientProperty("jlshell.updating", true);
                box.setModel(new DefaultComboBoxModel<>(copy.toArray())); box.setSelectedItem(selection);
                box.putClientProperty("jlshell.updating", false);
            }});
        };
        source.getItems().addListener(listener); removers.add(() -> source.getItems().removeListener(listener));
        boolean fontPicker=Boolean.TRUE.equals(source.getProperties().get("jlshell.fontPicker"));
        boolean fontGroups=Boolean.TRUE.equals(source.getProperties().get("jlshell.fontGroups"));
        @SuppressWarnings("unchecked") java.util.Set<String> monoFamilies=(java.util.Set<String>)source.getProperties().getOrDefault("jlshell.monospacedFonts",java.util.Set.of());
        String monoTitle=i18n.get("preferences.font.monospaced"),proportionalTitle=i18n.get("preferences.font.proportional");
        return () -> {
            JComboBox<Object> box = new JComboBox<>(entries.get().toArray()); view.set(box);
            box.putClientProperty("jlshell.fontPicker",fontPicker);box.setMaximumRowCount(fontPicker?14:12);
            box.setRenderer(new com.jlshell.ui.swing.idea.IdeaComboBoxRenderer(this::display,fontPicker,fontGroups,monoFamilies,monoTitle,proportionalTitle));
            box.setSelectedItem(selected.get());
            box.addActionListener(event -> { if (!Boolean.TRUE.equals(box.getClientProperty("jlshell.updating"))) {
                Object value = box.getSelectedItem(); FxThread.run(() -> { if (!closed) source.setValue(value); });
            }});
            return box;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Supplier<JComponent> list(ListView source) {
        AtomicReference<JList<Object>> view = new AtomicReference<>();
        AtomicReference<List<Object>> entries = new AtomicReference<>(new ArrayList<>(source.getItems()));
        String id = source.getId();
        var selected = observe(source.getSelectionModel().selectedItemProperty(), next -> {
            if (view.get() != null) { view.get().putClientProperty("jlshell.updating", true);
                view.get().setSelectedValue(next, true); view.get().putClientProperty("jlshell.updating", false); }
        });
        javafx.collections.ListChangeListener<Object> listener = change -> {
            List<Object> copy = new ArrayList<>(source.getItems()); entries.set(copy);
            SwingUtilities.invokeLater(() -> { if (!closed && view.get() != null) {
                view.get().putClientProperty("jlshell.updating", true); view.get().setListData(copy.toArray());
                view.get().putClientProperty("jlshell.updating", false);
            }});
        };
        source.getItems().addListener(listener); removers.add(() -> source.getItems().removeListener(listener));
        return () -> {
            JList<Object> list = new JList<>(entries.get().toArray()); view.set(list); list.setVisibleRowCount(8);
            list.setFixedCellHeight(34); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, display(value), index, selected, focus);
                    label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10)); return label;
                }
            });
            list.setSelectedValue(selected.get(), true);
            list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()
                    && !Boolean.TRUE.equals(list.getClientProperty("jlshell.updating"))) {
                Object value = list.getSelectedValue(); FxThread.run(() -> {
                    if (!closed) { if (value == null) source.getSelectionModel().clearSelection();
                        else source.getSelectionModel().select(value); }
                });
            }});
            list.setName(id);
            JScrollPane scroll = new JScrollPane(list); scroll.setMinimumSize(new Dimension(160, 140)); return scroll;
        };
    }

    private Supplier<JComponent> check(CheckBox source) {
        AtomicReference<JCheckBox> view = new AtomicReference<>();
        var selected = observe(source.selectedProperty(), value -> { if (view.get() != null) view.get().setSelected(value); });
        String text = source.getText();
        return () -> { JCheckBox check = new JCheckBox(text, selected.get()); view.set(check);
            check.addActionListener(e -> { boolean value = check.isSelected(); FxThread.run(() -> source.setSelected(value)); }); return check; };
    }

    private Supplier<JComponent> button(ButtonBase source) {
        AtomicReference<JButton> view = new AtomicReference<>();
        var text = observe(source.textProperty(), value -> { if (view.get() != null) view.get().setText(value); });
        boolean link = source instanceof Hyperlink || "connection-test".equals(source.getId())
                || source.getStyleClass().contains("shortcut-key-button") || source.getStyleClass().contains("shortcut-clear-button");
        Supplier<JComponent> graphic = source.getGraphic()==null?null:prepare(source.getGraphic());
        Object shortcut=source.getProperties().get(SwingShortcutRecording.KEY);
        String tooltip=source.getTooltip()==null?null:source.getTooltip().getText();
        return () -> { JButton button = new JButton(text.get()); view.set(button);
            button.setToolTipText(tooltip);
            if(graphic!=null) {
                JComponent icon=graphic.get();button.setLayout(new java.awt.BorderLayout(8,0));button.add(icon);
                forwardGraphicClick(icon,button);
            }
            if (link) { button.putClientProperty("JButton.buttonType", "borderless"); button.setForeground(UIManager.getColor("Component.focusColor")); }
            if(shortcut instanceof SwingShortcutRecording.Binding binding) {
                Runnable cancel=SwingShortcutRecording.install(button,binding);
                removers.add(()->SwingUtilities.invokeLater(cancel));
            } else button.addActionListener(e -> FxThread.run(() -> { if (!closed) source.fire(); }));
            return button; };
    }

    private static void forwardGraphicClick(Component component,JButton button) {
        component.addMouseListener(new java.awt.event.MouseAdapter(){
            public void mouseClicked(java.awt.event.MouseEvent event){if(button.isEnabled())button.doClick();}
        });
        if(component instanceof java.awt.Container container)for(Component child:container.getComponents())forwardGraphicClick(child,button);
    }

    private Supplier<JComponent> label(Label source) {
        AtomicReference<JComponent> view = new AtomicReference<>();
        var text = observe(source.textProperty(), value -> { if (view.get() instanceof JLabel label) label.setText(value);
            else if (view.get() instanceof JTextArea area) area.setText(value); });
        boolean wrap = source.isWrapText();
        return () -> {
            JComponent component;
            if (wrap) {
                JTextArea area = settingsLayout?new SwingWrappingLabel(text.get()):new JTextArea(text.get()); area.setEditable(false); area.setFocusable(false);
                area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false); area.setBorder(null);
                if(!settingsLayout){area.setColumns(40); area.setRows(Math.max(1, Math.min(8, (text.get().length() + 59) / 60)));}
                component = area;
            } else { JLabel label = new JLabel(text.get()); label.putClientProperty("html.disable", true); component = label; }
            view.set(component); return component;
        };
    }

    private Supplier<JComponent> tabs(TabPane source) {
        List<Supplier<JComponent>> children = source.getTabs().stream().map(tab -> prepare(tab.getContent())).toList();
        List<String> titles = source.getTabs().stream().map(Tab::getText).toList();
        if(source.getStyleClass().contains("plugin-external-tabs")) {
            AtomicReference<JPanel> cards=new AtomicReference<>();
            var selection=observe(source.getSelectionModel().selectedIndexProperty(),next->{if(cards.get()!=null)((java.awt.CardLayout)cards.get().getLayout()).show(cards.get(),next.toString());});
            return ()->{JPanel panel=new JPanel(new java.awt.CardLayout());panel.setOpaque(false);cards.set(panel);
                for(int i=0;i<children.size();i++)panel.add(children.get(i).get(),Integer.toString(i));
                ((java.awt.CardLayout)panel.getLayout()).show(panel,selection.get().toString());return panel;};
        }
        AtomicReference<JTabbedPane> view = new AtomicReference<>();
        for (int index = 0; index < source.getTabs().size(); index++) {
            int i = index;
            observe(source.getTabs().get(i).disableProperty(), disabled -> { if (view.get() != null) view.get().setEnabledAt(i, !disabled); });
        }
        List<Boolean> disabled = source.getTabs().stream().map(Tab::isDisable).toList();
        var selected=observe(source.getSelectionModel().selectedIndexProperty(),next->{if(view.get()!=null&&next.intValue()>=0)view.get().setSelectedIndex(next.intValue());});
        return () -> { JTabbedPane tabs = new JTabbedPane(); view.set(tabs);
            tabs.putClientProperty("JTabbedPane.showTabSeparators", false); tabs.putClientProperty("JTabbedPane.tabHeight", 34);
            for (int i = 0; i < children.size(); i++) { tabs.addTab(titles.get(i), children.get(i).get()); tabs.setEnabledAt(i, !disabled.get(i)); }
            if(selected.get().intValue()>=0)tabs.setSelectedIndex(selected.get().intValue());
            tabs.addChangeListener(event->{int next=tabs.getSelectedIndex();FxThread.run(()->{if(!closed)source.getSelectionModel().select(next);});});
            return tabs; };
    }

    private Supplier<JComponent> pane(Pane source) {
        List<Node> nodes = List.copyOf(source.getChildren());
        List<Supplier<JComponent>> children = nodes.stream().map(this::prepare).toList();
        if(source.getStyleClass().contains("plugin-toolbar"))return ()->{
            JPanel toolbar=new JPanel(new GridBagLayout());toolbar.setOpaque(false);
            for(int i=0;i<children.size();i++){
                JComponent child=children.get(i).get();GridBagConstraints c=new GridBagConstraints();
                c.gridx=i==0?0:i-1;c.gridy=i==0?0:1;c.gridwidth=i==0?Math.max(1,children.size()-1):1;
                c.weightx=i<=1?1:0;c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.WEST;
                c.insets=new Insets(i==0?0:6,0,0,i>0&&i<children.size()-1?6:0);
                if(i==0){if(child instanceof JTextField field)field.setColumns(0);child.setMinimumSize(new Dimension(0,child.getPreferredSize().height));}
                toolbar.add(child,c);
            }return toolbar;
        };
        var padding = source.getPadding();
        boolean horizontal = source instanceof HBox;
        boolean grid = source instanceof GridPane;
        if(source instanceof javafx.scene.text.TextFlow&&source.getStyleClass().contains("api-code-flow")) {
            record Fragment(String text,List<String> styles,Supplier<JComponent> component) {}
            List<Fragment> fragments=new ArrayList<>();
            for(int i=0;i<nodes.size();i++) {
                Node node=nodes.get(i);
                fragments.add(node instanceof javafx.scene.text.Text text
                        ?new Fragment(text.getText(),List.copyOf(text.getStyleClass()),null):new Fragment(null,List.of(),children.get(i)));
            }
            return ()->{JTextPane text=new JTextPane();text.setEditable(false);text.setOpaque(false);
                text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));text.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
                for(Fragment fragment:fragments)try {
                    text.setCaretPosition(text.getDocument().getLength());
                    if(fragment.component()!=null)text.insertComponent(fragment.component().get());
                    else {
                        var attributes=new javax.swing.text.SimpleAttributeSet();
                        javax.swing.text.StyleConstants.setFontFamily(attributes,Font.MONOSPACED);
                        java.awt.Color color=UIManager.getColor("Label.foreground");
                        if(fragment.styles().contains("api-code-string"))color=SwingTheme.color("secondary");
                        else if(fragment.styles().contains("api-code-url"))color=UIManager.getColor("JLShell.formLink");
                        else if(fragment.styles().contains("api-code-keyword"))color=UIManager.getColor("Component.focusColor");
                        javax.swing.text.StyleConstants.setForeground(attributes,color);
                        text.getDocument().insertString(text.getDocument().getLength(),fragment.text(),attributes);
                    }
                }catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}
                JPanel panel=new JPanel(new java.awt.BorderLayout());panel.setOpaque(false);panel.add(text);return panel;};
        }
        if (source instanceof javafx.scene.text.TextFlow || source instanceof FlowPane || source instanceof TilePane) {
            return ()->{JPanel panel=new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,4,4));panel.setOpaque(false);
                for(var child:children)panel.add(child.get());return panel;};
        }
        boolean equalColumns=grid&&!((GridPane)source).getColumnConstraints().isEmpty()
                &&((GridPane)source).getColumnConstraints().stream().allMatch(column->column.getPercentWidth()>0);
        boolean actionsRow=source.getStyleClass().contains("idea-actions-row");
        boolean settingsForm = settingsLayout && source.getStyleClass().contains("settings-form");
        int gap = (int) (horizontal ? ((HBox) source).getSpacing() : source instanceof VBox vbox ? vbox.getSpacing() : 0);
        int labelWidth = grid && !((GridPane) source).getColumnConstraints().isEmpty()
                ? Math.max(0, (int)((GridPane) source).getColumnConstraints().getFirst().getPrefWidth()) : 0;
        List<GridBagConstraints> layout = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node child = nodes.get(i); GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.NORTHWEST; c.fill = GridBagConstraints.HORIZONTAL;
            if (grid) {
                c.anchor = GridBagConstraints.WEST;
                c.gridx = GridPane.getColumnIndex(child) == null ? 0 : GridPane.getColumnIndex(child);
                c.gridy = GridPane.getRowIndex(child) == null ? 0 : GridPane.getRowIndex(child);
                c.gridwidth = GridPane.getColumnSpan(child) == null ? 1 : GridPane.getColumnSpan(child);
                c.weightx = equalColumns?1:settingsForm ? 0 : c.gridx > 0 || c.gridwidth > 1 ? 1 : 0;
                if(settingsLayout&&child instanceof Region region&&region.getPrefWidth()>0&&!equalColumns)c.fill=GridBagConstraints.NONE;
                c.insets = new Insets(settingsLayout ? 3 : 5, 0, settingsLayout ? 3 : 5, c.gridx == 0 && c.gridwidth == 1 ? 12 : 0);
            } else {
                c.gridx = horizontal ? i : 0; c.gridy = horizontal ? 0 : i;
                c.weightx = horizontal ? (HBox.getHgrow(child) == Priority.ALWAYS ? 1 : 0) : 1;
                c.weighty = horizontal || VBox.getVgrow(child) == Priority.ALWAYS ? 1 : 0;
                if (c.weighty > 0) c.fill = GridBagConstraints.BOTH;
                if (settingsLayout && horizontal && !(child instanceof Separator) && HBox.getHgrow(child) != Priority.ALWAYS)
                    c.fill = GridBagConstraints.NONE;
                c.insets = new Insets(0, 0, !horizontal && i < nodes.size()-1 ? gap : 0, horizontal && i < nodes.size()-1 ? gap : 0);
            }
            if(actionsRow) {c.weightx=i==0?1:0;c.fill=GridBagConstraints.HORIZONTAL;c.weighty=0;}
            layout.add(c);
        }
        return () -> {
            GridBagLayout gridBag = new GridBagLayout();
            if (grid && labelWidth > 0) gridBag.columnWidths = settingsForm ? new int[]{labelWidth, 340, 0} : new int[]{labelWidth, 0};
            JPanel panel = new JPanel(gridBag); panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder((int)padding.getTop(), (int)padding.getLeft(), (int)padding.getBottom(), (int)padding.getRight()));
            for (int i = 0; i < children.size(); i++) {
                JComponent child=children.get(i).get();
                if(actionsRow&&i>0) {child.setPreferredSize(new Dimension(180,28));child.setMinimumSize(new Dimension(160,28));}
                panel.add(child, layout.get(i));
            }
            if (horizontal && layout.stream().noneMatch(c -> c.weightx > 0)) {
                GridBagConstraints glue = new GridBagConstraints(); glue.gridx = children.size();
                glue.weightx = 1; glue.fill = GridBagConstraints.HORIZONTAL; panel.add(Box.createHorizontalGlue(), glue);
            }
            if (settingsForm) {
                GridBagConstraints space=new GridBagConstraints(); space.gridx=2; space.gridy=0; space.weightx=1;
                panel.add(Box.createHorizontalGlue(),space);
            }
            // The content is anchored at the top, matching DialogWrapper's center-panel layout.
            if (layout.stream().noneMatch(c -> c.weighty > 0) && !horizontal) {
                GridBagConstraints glue = new GridBagConstraints(); glue.gridy = grid ? 100 : children.size();
                glue.weighty = 1; glue.fill = GridBagConstraints.VERTICAL; panel.add(Box.createVerticalGlue(), glue);
            }
            return panel;
        };
    }

    private String display(Object value) {
        if (value == null) return "";
        if (value instanceof ProjectProfile item) return item.name();
        if (value instanceof FolderProfile item) return item.name();
        if (value instanceof VaultEntryProfile item) return item.name() + (item.id() == null ? "" : "  ·  " + i18n.get("connection.auth." + item.authenticationType().name()));
        if (value instanceof com.jlshell.ui.theme.AccentColor item) return item.displayName();
        if (value instanceof AuthenticationType item) return i18n.get("connection.auth." + item.name());
        if (value instanceof HostKeyVerificationMode item) return i18n.get("connection.hostKey." + item.name());
        if (value instanceof ConnectionType item) return item == ConnectionType.SSH ? "SSH" : i18n.get("connection.type.localShell");
        if (value instanceof VaultEncryptionMode item) return i18n.get("vault.encMode." + item.name());
        return value.toString();
    }

    private static void setEnabled(JComponent component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof JScrollPane scroll) scroll.getViewport().getView().setEnabled(enabled);
    }

    public void close() { closed = true; removers.forEach(Runnable::run); removers.clear(); }
}
