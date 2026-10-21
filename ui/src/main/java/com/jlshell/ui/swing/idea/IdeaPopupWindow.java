package com.jlshell.ui.swing.idea;

import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import com.formdev.flatlaf.util.SystemInfo;
import com.jlshell.ui.swing.SwingTheme;
import javax.swing.JWindow;
import java.awt.Color;
import java.awt.GraphicsDevice;

/** Windows 保持不透明文字表面，圆角交给 DWM；避免透明窗口使 LCD 文字退化。 */
final class IdeaPopupWindow {
    static final String NATIVE_BORDER = "JLShell.nativePopupBorder";
    private IdeaPopupWindow() {}

    static boolean configure(JWindow window, boolean tooltip) {
        boolean alpha = !SystemInfo.isWindows && window.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        window.setBackground(alpha ? new Color(0, true) : SwingTheme.color(tooltip ? "tooltip" : "popup"));
        window.getRootPane().putClientProperty("Window.shadow", SystemInfo.isWindows);
        return alpha;
    }

    /** 在 pack 创建 native peer 后调用。旧 Windows 或原生库不可用时保留清晰的不透明回退。 */
    static void decorate(JWindow window, boolean tooltip) {
        if (!SystemInfo.isWindows_11_orLater || !FlatNativeWindowsLibrary.isLoaded()) return;
        long hwnd = FlatNativeWindowsLibrary.getHWND(window);
        if (hwnd == 0) return;
        boolean rounded = FlatNativeWindowsLibrary.setWindowCornerPreference(hwnd, FlatNativeWindowsLibrary.DWMWCP_ROUND);
        if (rounded) {
            FlatNativeWindowsLibrary.dwmSetWindowAttributeCOLORREF(hwnd, FlatNativeWindowsLibrary.DWMWA_BORDER_COLOR,
                    SwingTheme.color(tooltip ? "tooltipBorder" : "popupBorder"));
            window.getRootPane().putClientProperty(NATIVE_BORDER, true);
        }
    }
}
