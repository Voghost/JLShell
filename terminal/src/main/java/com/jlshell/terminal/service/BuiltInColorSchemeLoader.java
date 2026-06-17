package com.jlshell.terminal.service;

import java.awt.Color;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 从 classpath 加载内置终端配色方案。
 * themes.json 包含约 300 个预设方案，首次调用时惰性加载并缓存。
 */
public class BuiltInColorSchemeLoader {

    private static final Logger log = LoggerFactory.getLogger(BuiltInColorSchemeLoader.class);
    private static final String RESOURCE_PATH = "/themes/themes.json";

    private volatile List<TerminalColorScheme> cached;

    public List<TerminalColorScheme> loadAll() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = doLoad();
                }
            }
        }
        return cached;
    }

    public Optional<TerminalColorScheme> findByName(String name) {
        return loadAll().stream()
                .filter(s -> s.name().equals(name))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private List<TerminalColorScheme> doLoad() {
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream(RESOURCE_PATH), StandardCharsets.UTF_8)) {
            List<Map<String, String>> raw = new Gson().fromJson(reader,
                    new TypeToken<List<Map<String, String>>>() {}.getType());

            List<TerminalColorScheme> schemes = raw.stream()
                    .map(this::parseScheme)
                    .toList();

            log.info("Loaded {} built-in terminal color schemes", schemes.size());
            return Collections.unmodifiableList(schemes);
        } catch (Exception e) {
            log.error("Failed to load built-in color schemes", e);
            return Collections.emptyList();
        }
    }

    private TerminalColorScheme parseScheme(Map<String, String> entry) {
        return new TerminalColorScheme(
                entry.get("name"),
                parseColor(entry.get("background")),
                parseColor(entry.get("foreground")),
                parseColor(entry.get("cursorColor")),
                parseColor(entry.get("selectionBackground"), new Color(0x2d, 0x5f, 0xa3)),
                parseColor(entry.get("selectionForeground"), Color.WHITE),
                parseColor(entry.get("hyperlinkColor"), new Color(0x4d, 0x9c, 0xf8)),
                parseColor(entry.get("searchMatchBackground"), new Color(0xe0, 0xb1, 0x2d)),
                parseColor(entry.get("searchMatchForeground"), parseColor(entry.get("background"))),
                parseColor(entry.get("black")),
                parseColor(entry.get("red")),
                parseColor(entry.get("green")),
                parseColor(entry.get("yellow")),
                parseColor(entry.get("blue")),
                parseColor(entry.get("purple")),
                parseColor(entry.get("cyan")),
                parseColor(entry.get("white")),
                parseColor(entry.get("brightBlack")),
                parseColor(entry.get("brightRed")),
                parseColor(entry.get("brightGreen")),
                parseColor(entry.get("brightYellow")),
                parseColor(entry.get("brightBlue")),
                parseColor(entry.get("brightPurple")),
                parseColor(entry.get("brightCyan")),
                parseColor(entry.get("brightWhite")),
                1.0
        );
    }

    private Color parseColor(String hex) {
        return parseColor(hex, Color.BLACK);
    }

    private Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new Color(Integer.parseInt(h, 16), false);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
