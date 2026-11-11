package com.jlshell.ui.support;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 延迟注册终端使用的 AWT Nerd Font。 */
public final class BundledFontLoader {

    private static final Logger log = LoggerFactory.getLogger(BundledFontLoader.class);

    public static final String BUNDLED_FONT_FAMILY = "SauceCodePro Nerd Font Mono";

    private static final Object AWT_LOCK = new Object();
    private static volatile boolean awtRegistered = false;

    private BundledFontLoader() {}

    /**
     * 首次创建终端时调用：把 TTF 注册到 AWT GraphicsEnvironment，
     * 让 JediTerm (Swing) 能按 family 名找到 Nerd Font。
     * 双检锁保证只注册一次，后续调用直接返回。
     */
    public static void ensureAwtRegistered() {
        if (awtRegistered) return;
        synchronized (AWT_LOCK) {
            if (awtRegistered) return;
            registerAwtFont("/fonts/SauceCodeProNerdFontMono-Regular.ttf");
            registerAwtFont("/fonts/SauceCodeProNerdFontMono-Bold.ttf");
            awtRegistered = true;
            log.info("Bundled AWT fonts registered: {}", BUNDLED_FONT_FAMILY);
        }
    }

    private static void registerAwtFont(String resourcePath) {
        try (InputStream is = BundledFontLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Bundled font not found for AWT: {}", resourcePath);
                return;
            }
            java.awt.Font awtFont = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, is);
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(awtFont);
            log.debug("AWT font registered: {} ({})", awtFont.getFontName(), resourcePath);
        } catch (Exception e) {
            log.warn("Error registering AWT font: {}", resourcePath, e);
        }
    }
}
