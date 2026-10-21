package com.jlshell.ui.swing;

import javafx.application.Platform;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Native settings file selection, with the same FX model nested-loop contract as dialogs. */
public final class SwingSettingsFileChooser {
    private SwingSettingsFileChooser() {}
    public static File open(String title, boolean directory, String extension) {
        if(!Platform.isFxApplicationThread())throw new IllegalStateException("Settings model requires FX thread");
        Object loop=new Object();AtomicReference<File> result=new AtomicReference<>();AtomicReference<RuntimeException> failure=new AtomicReference<>();
        SwingUtilities.invokeLater(()->{
            try {
                JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(directory?JFileChooser.DIRECTORIES_ONLY:JFileChooser.FILES_ONLY);
                if(extension!=null)chooser.setFileFilter(new FileNameExtensionFilter("*."+extension,extension));
                var owner=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                if(chooser.showOpenDialog(owner)==JFileChooser.APPROVE_OPTION)result.set(chooser.getSelectedFile());
            }catch(RuntimeException error){failure.set(error);}
            finally{Platform.runLater(()->Platform.exitNestedEventLoop(loop,null));}
        });
        Platform.enterNestedEventLoop(loop);
        if(failure.get()!=null)throw failure.get();return result.get();
    }
}
