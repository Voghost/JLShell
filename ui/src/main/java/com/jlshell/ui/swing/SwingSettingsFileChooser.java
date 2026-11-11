package com.jlshell.ui.swing;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;

/** Native open/save selection, delivered asynchronously on EDT. */
public final class SwingSettingsFileChooser {
    private SwingSettingsFileChooser() {}
    public static java.util.concurrent.CompletableFuture<File> openAsync(String title, boolean directory, String extension) {
        return chooseAsync(title, directory, extension, null, false);
    }
    public static java.util.concurrent.CompletableFuture<File> saveAsync(String title, String name, String extension) {
        return chooseAsync(title, false, extension, name, true);
    }
    private static java.util.concurrent.CompletableFuture<File> chooseAsync(String title, boolean directory, String extension, String name, boolean save) {
        var result = new java.util.concurrent.CompletableFuture<File>();
        Runnable show = () -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                if (extension != null) chooser.setFileFilter(new FileNameExtensionFilter("*." + extension, extension));
                if (name != null) chooser.setSelectedFile(new File(name));
                int selected = save ? chooser.showSaveDialog(SwingDialogChrome.owner()) : chooser.showOpenDialog(SwingDialogChrome.owner());
                result.complete(selected == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null);
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        };
        if (SwingUtilities.isEventDispatchThread()) show.run(); else SwingUtilities.invokeLater(show);
        return result;
    }
}
