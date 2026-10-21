package com.jlshell.ui.swing;

import com.jlshell.ui.support.FxThread;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;

/** Shared asynchronous plugin artwork; no network or image decoding on UI threads. */
public final class PluginIcons {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final java.util.concurrent.Executor LOADER = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "plugin-icon-loader"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, Source> REMOTE = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) { return size() > 128; }
    };
    private static final ClassValue<Source> LOCAL = new ClassValue<>() {
        @Override protected Source computeValue(Class<?> type) {
            return new Source(CompletableFuture.supplyAsync(() -> loadLocal(type), LOADER));
        }
    };
    private PluginIcons() {}

    public record Source(CompletableFuture<BufferedImage> image) {}

    public static Source local(Class<?> plugin) { return plugin == null ? null : LOCAL.get(plugin); }

    public static Source remote(String location) {
        if (location == null || location.isBlank()) return null;
        try {
            URL url = java.net.URI.create(location).toURL();
            if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) return null;
            synchronized (REMOTE) {
                return REMOTE.computeIfAbsent(location, key -> new Source(CompletableFuture.supplyAsync(() -> load(url), LOADER)));
            }
        } catch (Exception invalid) { return null; }
    }

    private static BufferedImage loadLocal(Class<?> plugin) {
        try {
            var source = plugin.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation().getProtocol().equals("file")) {
                Path jar = Path.of(source.getLocation().toURI());
                if (jar.toString().endsWith(".jar")) try (JarFile archive = new JarFile(jar.toFile())) {
                    var manifest = archive.getManifest();
                    String icon = manifest == null ? null : manifest.getMainAttributes().getValue("JLShell-Plugin-Icon");
                    if (icon != null && !icon.isBlank()) {
                        BufferedImage image = load(new URL("jar:" + source.getLocation().toExternalForm() + "!/" + icon.replaceFirst("^/", "")));
                        if (image != null) return image;
                    }
                }
            }
            for (String path : new String[]{"/META-INF/jlshell/icon.png", "/icons/plugin.png", "/icon.png"}) {
                URL url = plugin.getResource(path);
                if (url != null) {
                    BufferedImage image = load(url);
                    if (image != null) return image;
                }
            }
        } catch (Exception ignored) { /* Optional artwork must not break plugin pages. */ }
        return null;
    }

    private static BufferedImage load(URL url) {
        try {
            var connection = url.openConnection();
            connection.setUseCaches(false);
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            byte[] bytes;
            try (var input = connection.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) return null;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return null;
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) return null;
                    BufferedImage image = reader.read(0);
                    if (Math.max(image.getWidth(), image.getHeight()) <= 128) return image;
                    double scale = 128d / Math.max(image.getWidth(), image.getHeight());
                    BufferedImage small = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)),
                            Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = small.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null);
                    } finally { graphics.dispose(); }
                    return small;
                } finally { reader.dispose(); }
            }
        } catch (Exception unavailable) { return null; }
    }

    public static StackPane avatar(String name, Source source) {
        Label fallback = new Label(initial(name));
        fallback.getStyleClass().add("plugin-avatar-text");
        StackPane avatar = new StackPane(fallback);
        avatar.getStyleClass().add("plugin-avatar");
        avatar.getProperties().put("swing.pluginIconSource", source);
        if (source != null) source.image().thenAccept(image -> {
            if (image == null) return;
            FxThread.run(() -> {
                ImageView view = new ImageView(SwingFXUtils.toFXImage(image, null));
                view.setFitWidth(40); view.setFitHeight(40); view.setPreserveRatio(true);
                avatar.getChildren().setAll(fallback, view); fallback.setVisible(false);
            });
        });
        return avatar;
    }

    public static Icon icon(String name, Source source, int size, boolean monochrome) {
        return new Artwork(initial(name), source, size, monochrome);
    }

    private static String initial(String name) {
        String value = name == null ? "" : name.strip();
        return value.isEmpty() ? "P" : new String(Character.toChars(value.codePointAt(0))).toUpperCase(java.util.Locale.ROOT);
    }

    private static final class Artwork implements Icon {
        private final String initial;
        private final Source source;
        private final int size;
        private final boolean monochrome;
        private final Map<Component, Boolean> viewers = new WeakHashMap<>();
        private BufferedImage tinted;
        private int tintColor;
        Artwork(String initial, Source source, int size, boolean monochrome) {
            this.initial = initial; this.source = source; this.size = size; this.monochrome = monochrome;
            if (source != null) source.image().thenRun(() -> SwingUtilities.invokeLater(() -> viewers.keySet().forEach(Component::repaint)));
        }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            viewers.put(component, true);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                BufferedImage image = source == null ? null : source.image().getNow(null);
                Color foreground = component.getForeground();
                if (image != null) {
                    if (monochrome) image = tint(image, foreground);
                    double scale = (double) size / Math.max(image.getWidth(), image.getHeight());
                    int w = Math.max(1, (int) (image.getWidth() * scale)), h = Math.max(1, (int) (image.getHeight() * scale));
                    g.drawImage(image, (size - w) / 2, (size - h) / 2, w, h, null);
                } else {
                    if (!monochrome) {
                        Color accent = UIManager.getColor("Component.focusColor");
                        g.setColor(accent == null ? new Color(0x3574f0) : accent);
                        g.fillRoundRect(0, 0, size, size, 10, 10);
                    }
                    g.setColor(monochrome ? foreground : Color.WHITE);
                    g.setFont(component.getFont().deriveFont(Font.BOLD, Math.max(12, size * .48f)));
                    var fm = g.getFontMetrics();
                    g.drawString(initial, (size - fm.stringWidth(initial)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
                }
            } finally { g.dispose(); }
        }
        private BufferedImage tint(BufferedImage original, Color color) {
            if (tinted != null && tintColor == color.getRGB()) return tinted;
            tintColor = color.getRGB();
            tinted = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < original.getHeight(); y++) for (int x = 0; x < original.getWidth(); x++) {
                int argb = original.getRGB(x, y);
                double luminance = (((argb >> 16) & 255) * .2126 + ((argb >> 8) & 255) * .7152 + (argb & 255) * .0722) / 255;
                int alpha = (int) ((argb >>> 24) * (.35 + .65 * luminance));
                tinted.setRGB(x, y, (alpha << 24) | (color.getRGB() & 0xffffff));
            }
            return tinted;
        }
    }
}
