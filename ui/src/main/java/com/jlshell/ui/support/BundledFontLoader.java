package com.jlshell.ui.support;

import java.io.InputStream;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads bundled Nerd Font TTF files so they are available to both
 * JavaFX (via Font.loadFont) and AWT/Swing terminal (via Font.createFont).
 *
 * JavaFX 字体在启动时同步加载（CSS -fx-font-family 需要它）；
 * AWT 注册延迟到首次创建终端时（{@link #ensureAwtRegistered()}），
 * 把 2MB TTF 的二次 I/O 从启动路径移到首次开终端。
 */
public final class BundledFontLoader {

    private static final Logger log = LoggerFactory.getLogger(BundledFontLoader.class);

    public static final String BUNDLED_FONT_FAMILY = "SauceCodePro Nerd Font Mono";

    private static volatile boolean javafxLoaded = false;
    private static volatile boolean awtRegistered = false;

    private BundledFontLoader() {}

    /** 加载 JavaFX 字体。必须在 JavaFX init 阶段调用（FX 线程或 FX init 线程）。 */
    public static synchronized void load() {
        if (javafxLoaded) return;

        loadFont("/fonts/SauceCodeProNerdFontMono-Regular.ttf");
        loadFont("/fonts/SauceCodeProNerdFontMono-Bold.ttf");

        javafxLoaded = true;
        log.info("Bundled JavaFX fonts loaded: {}", BUNDLED_FONT_FAMILY);
    }

    /**
     * 首次创建终端时调用：把 TTF 注册到 AWT GraphicsEnvironment，
     * 让 JediTerm (Swing) 能按 family 名找到 Nerd Font。
     * 双检锁保证只注册一次，后续调用直接返回。
     */
    public static void ensureAwtRegistered() {
        if (awtRegistered) return;
        synchronized (BundledFontLoader.class) {
            if (awtRegistered) return;
            registerAwtFont("/fonts/SauceCodeProNerdFontMono-Regular.ttf");
            registerAwtFont("/fonts/SauceCodeProNerdFontMono-Bold.ttf");
            awtRegistered = true;
            log.info("Bundled AWT fonts registered: {}", BUNDLED_FONT_FAMILY);
        }
    }

    private static void loadFont(String resourcePath) {
        try (InputStream is = BundledFontLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("Bundled font not found: {}", resourcePath);
                return;
            }
            Font font = Font.loadFont(is, -1);
            if (font != null) {
                log.debug("JavaFX font loaded: {} ({})", font.getName(), resourcePath);
            } else {
                log.warn("Failed to load JavaFX font: {}", resourcePath);
            }
        } catch (Exception e) {
            log.warn("Error loading bundled font: {}", resourcePath, e);
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
