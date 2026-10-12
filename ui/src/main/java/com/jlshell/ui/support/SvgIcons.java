package com.jlshell.ui.support;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/** 缓存 SVG 路径数据；每个调用方仍拥有独立的 JavaFX 节点。 */
public final class SvgIcons {

    private static final Map<String, String> PATHS = new ConcurrentHashMap<>();
    private static final Pattern PATH_ATTRIBUTE = Pattern.compile("\\bd\\s*=\\s*\"([^\"]+)\"");

    private SvgIcons() {}

    public static Region create(String resource, double size) {
        Region icon = new Region();
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        setPath(icon, resource);
        icon.getStyleClass().add("action-bar-icon");
        return icon;
    }

    public static void setPath(Region icon, String resource) {
        // 单元格复用时无需重新解析同一张图标。
        if (resource.equals(icon.getProperties().get(SvgIcons.class))) return;
        String data = PATHS.computeIfAbsent(resource, SvgIcons::readPath);
        SVGPath shape = new SVGPath();
        shape.setContent(data);
        icon.setShape(shape);
        icon.setScaleShape(true);
        icon.setCenterShape(true);
        icon.getProperties().put(SvgIcons.class, resource);
    }

    private static String readPath(String resource) {
        try (var stream = SvgIcons.class.getResourceAsStream(resource)) {
            if (stream == null) return "";
            var matcher = PATH_ATTRIBUTE.matcher(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            while (matcher.find()) data.append(matcher.group(1)).append(' ');
            return data.toString();
        } catch (java.io.IOException ignored) {
            return "";
        }
    }
}
