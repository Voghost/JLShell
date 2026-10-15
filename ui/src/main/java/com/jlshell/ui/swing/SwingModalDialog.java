package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;

/** Modeless AWT peer with explicit ownership, compatible with JavaFX popup event delivery. */
final class SwingModalDialog extends JDialog {
    private boolean blocked;
    private boolean ownerWasEnabled;
    private Object previousModal;

    SwingModalDialog(Window owner, String title) {
        super(owner, title, Dialog.ModalityType.MODELESS);
    }

    @Override public void setVisible(boolean visible) {
        if (visible && !isVisible()) blockOwner();
        if (!visible) restoreOwner();
        try { super.setVisible(visible); }
        catch (RuntimeException | Error failure) { restoreOwner(); throw failure; }
    }

    @Override public void dispose() {
        // Windows chooses the next active window while hiding/destroying this peer.
        // Restore the owner first; WINDOW_CLOSED is too late for that choice.
        restoreOwner();
        super.dispose();
    }

    private void blockOwner() {
        Window owner = getOwner();
        if (blocked || owner == null) return;
        blocked = true;
        ownerWasEnabled = owner.isEnabled();
        if (owner instanceof RootPaneContainer host) {
            previousModal = host.getRootPane().getClientProperty("jlshell.settingsModal");
            host.getRootPane().putClientProperty("jlshell.settingsModal", true);
        }
        owner.setEnabled(false);
    }

    private void restoreOwner() {
        if (!blocked) return;
        blocked = false;
        Window owner = getOwner();
        if (owner instanceof RootPaneContainer host)
            host.getRootPane().putClientProperty("jlshell.settingsModal", previousModal);
        if (owner != null && owner.isDisplayable()) owner.setEnabled(ownerWasEnabled);
    }
}
