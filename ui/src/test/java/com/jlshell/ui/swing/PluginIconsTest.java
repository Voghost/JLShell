package com.jlshell.ui.swing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import static org.junit.jupiter.api.Assertions.*;

class PluginIconsTest {
    @TempDir Path directory;

    @Test void originalArtworkIsUsedAndRailUsesOnlyTheComponentForeground() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var artwork = artwork();
            var source = new PluginIcons.Source(CompletableFuture.completedFuture(artwork));
            JLabel label = new JLabel(); label.setForeground(new Color(0xbcbec4));
            for (boolean monochrome : new boolean[]{false, true}) {
                var icon = PluginIcons.icon("Fallback", source, 20, monochrome);
                BufferedImage rendered = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
                var graphics = rendered.createGraphics();
                try { icon.paintIcon(label, graphics, 0, 0); } finally { graphics.dispose(); }
                if (monochrome) {
                    for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) {
                        int pixel = rendered.getRGB(x, y);
                        if ((pixel >>> 24) != 0) {
                            Color actual = new Color(pixel, true), expected = label.getForeground();
                            // Premultiplied alpha round-trips can round channels by one unit.
                            assertTrue(Math.abs(actual.getRed() - expected.getRed()) <= 2);
                            assertTrue(Math.abs(actual.getGreen() - expected.getGreen()) <= 2);
                            assertTrue(Math.abs(actual.getBlue() - expected.getBlue()) <= 2);
                        }
                    }
                } else {
                    assertEquals(Color.RED.getRGB(), rendered.getRGB(4, 10));
                    assertEquals(Color.GREEN.getRGB(), rendered.getRGB(16, 10));
                }
            }
        });
    }

    @Test void marketplaceArtworkLoadsAsynchronouslyAndMissingArtworkFallsBack() throws Exception {
        var bytes = png();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/icon.png", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var source = PluginIcons.remote(base + "/icon.png");
            assertSame(source, PluginIcons.remote(base + "/icon.png"));
            assertEquals(Color.RED.getRGB(), source.image().get(10, TimeUnit.SECONDS).getRGB(0, 0));
            assertNull(PluginIcons.remote(base + "/missing").image().get(10, TimeUnit.SECONDS));
            assertNull(PluginIcons.remote(""));
            assertNull(PluginIcons.remote("file:///private/image.png"));
        } finally { server.stop(0); }
    }

    @Test void installedPluginCanDeclareItsOwnJarResourceWithoutNewSdkMethods() throws Exception {
        Path jar = directory.resolve("plugin.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("JLShell-Plugin-Icon", "branding/logo.png");
        String resource = IconFixture.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(java.nio.file.Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry(resource));
            try (var input = IconFixture.class.getClassLoader().getResourceAsStream(resource)) { input.transferTo(output); }
            output.closeEntry();
            output.putNextEntry(new JarEntry("branding/logo.png")); output.write(png()); output.closeEntry();
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> plugin = loader.loadClass(IconFixture.class.getName());
            var source = PluginIcons.local(plugin);
            assertSame(source, PluginIcons.local(plugin));
            assertEquals(Color.GREEN.getRGB(), source.image().get(10, TimeUnit.SECONDS).getRGB(19, 0));
        }
    }

    private static BufferedImage artwork() {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.RED); graphics.fillRect(0, 0, 10, 20); graphics.setColor(Color.GREEN); graphics.fillRect(10, 0, 10, 20); }
        finally { graphics.dispose(); }
        return image;
    }
    private static byte[] png() throws Exception { var bytes = new ByteArrayOutputStream(); ImageIO.write(artwork(), "png", bytes); return bytes.toByteArray(); }
    public static final class IconFixture {}
}
