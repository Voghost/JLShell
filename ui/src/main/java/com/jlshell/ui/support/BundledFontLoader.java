package com.jlshell.ui.support;

import java.io.InputStream;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads bundled Nerd Font TTF files so they are available to both
 * JavaFX (via Font.loadFont) and AWT/Swing terminal (via Font.createFont).
 */
public final class BundledFontLoader {

    private static final Logger log = LoggerFactory.getLogger(BundledFontLoader.class);

    public static final String BUNDLED_FONT_FAMILY = "SauceCodePro Nerd Font Mono";

    private static boolean loaded = false;

    private BundledFontLoader() {}

    public static synchronized void load() {
        if (loaded) return;

        loadFont("/fonts/SauceCodeProNerdFontMono-Regular.ttf");
        loadFont("/fonts/SauceCodeProNerdFontMono-Bold.ttf");

        // Also register with AWT so the JediTerm terminal (Swing) can find it
        registerAwtFont("/fonts/SauceCodeProNerdFontMono-Regular.ttf");
        registerAwtFont("/fonts/SauceCodeProNerdFontMono-Bold.ttf");

        loaded = true;
        log.info("Bundled fonts loaded: {}", BUNDLED_FONT_FAMILY);
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
