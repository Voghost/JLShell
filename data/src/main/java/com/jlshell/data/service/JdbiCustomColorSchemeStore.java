package com.jlshell.data.service;

import java.awt.Color;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jlshell.data.dao.CustomColorSchemeDao;
import com.jlshell.data.entity.CustomColorSchemeEntity;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.CustomColorSchemeStore;

import org.jdbi.v3.core.Jdbi;

public class JdbiCustomColorSchemeStore implements CustomColorSchemeStore {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Jdbi jdbi;

    public JdbiCustomColorSchemeStore(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public List<TerminalColorScheme> listAll() {
        return jdbi.withExtension(CustomColorSchemeDao.class, dao ->
                dao.findAll().stream()
                        .map(this::toScheme)
                        .toList());
    }

    @Override
    public Optional<TerminalColorScheme> findByName(String name) {
        return jdbi.withExtension(CustomColorSchemeDao.class, dao ->
                dao.findByName(name).map(this::toScheme));
    }

    @Override
    public void save(TerminalColorScheme scheme) {
        jdbi.useExtension(CustomColorSchemeDao.class, dao -> {
            Optional<CustomColorSchemeEntity> existing = dao.findByName(scheme.name());
            if (existing.isPresent()) {
                CustomColorSchemeEntity entity = existing.get();
                entity.setColorsJson(toJson(scheme));
                entity.setUpdatedAt(Instant.now().toEpochMilli());
                dao.update(entity);
            } else {
                CustomColorSchemeEntity entity = new CustomColorSchemeEntity(
                        UUID.randomUUID().toString(), scheme.name(), toJson(scheme));
                dao.insert(entity);
            }
        });
    }

    @Override
    public void deleteByName(String name) {
        jdbi.useExtension(CustomColorSchemeDao.class, dao -> dao.deleteByName(name));
    }

    private TerminalColorScheme toScheme(CustomColorSchemeEntity entity) {
        return fromJson(entity.getColorsJson());
    }

    @SuppressWarnings("unchecked")
    private static TerminalColorScheme fromJson(String json) {
        Map<String, Object> map = GSON.fromJson(json, MAP_TYPE);
        return new TerminalColorScheme(
                (String) map.get("name"),
                parseColor(map.get("background")),
                parseColor(map.get("foreground")),
                parseColor(map.get("cursorColor")),
                parseColor(map.getOrDefault("selectionBackground", "#2d5fa3")),
                parseColor(map.getOrDefault("selectionForeground", "#ffffff")),
                parseColor(map.getOrDefault("hyperlinkColor", "#4d9cf8")),
                parseColor(map.getOrDefault("searchMatchBackground", "#e0b12d")),
                parseColor(map.getOrDefault("searchMatchForeground", "#1e1f22")),
                parseColor(map.get("black")),
                parseColor(map.get("red")),
                parseColor(map.get("green")),
                parseColor(map.get("yellow")),
                parseColor(map.get("blue")),
                parseColor(map.get("purple")),
                parseColor(map.get("cyan")),
                parseColor(map.get("white")),
                parseColor(map.get("brightBlack")),
                parseColor(map.get("brightRed")),
                parseColor(map.get("brightGreen")),
                parseColor(map.get("brightYellow")),
                parseColor(map.get("brightBlue")),
                parseColor(map.get("brightPurple")),
                parseColor(map.get("brightCyan")),
                parseColor(map.get("brightWhite")),
                ((Number) map.getOrDefault("opacity", 1.0)).doubleValue()
        );
    }

    private static String toJson(TerminalColorScheme scheme) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", scheme.name());
        map.put("background", formatColor(scheme.background()));
        map.put("foreground", formatColor(scheme.foreground()));
        map.put("cursorColor", formatColor(scheme.cursorColor()));
        map.put("selectionBackground", formatColor(scheme.selectionBackground()));
        map.put("selectionForeground", formatColor(scheme.selectionForeground()));
        map.put("hyperlinkColor", formatColor(scheme.hyperlinkColor()));
        map.put("searchMatchBackground", formatColor(scheme.searchMatchBackground()));
        map.put("searchMatchForeground", formatColor(scheme.searchMatchForeground()));
        map.put("black", formatColor(scheme.black()));
        map.put("red", formatColor(scheme.red()));
        map.put("green", formatColor(scheme.green()));
        map.put("yellow", formatColor(scheme.yellow()));
        map.put("blue", formatColor(scheme.blue()));
        map.put("purple", formatColor(scheme.purple()));
        map.put("cyan", formatColor(scheme.cyan()));
        map.put("white", formatColor(scheme.white()));
        map.put("brightBlack", formatColor(scheme.brightBlack()));
        map.put("brightRed", formatColor(scheme.brightRed()));
        map.put("brightGreen", formatColor(scheme.brightGreen()));
        map.put("brightYellow", formatColor(scheme.brightYellow()));
        map.put("brightBlue", formatColor(scheme.brightBlue()));
        map.put("brightPurple", formatColor(scheme.brightPurple()));
        map.put("brightCyan", formatColor(scheme.brightCyan()));
        map.put("brightWhite", formatColor(scheme.brightWhite()));
        map.put("opacity", scheme.opacity());
        return GSON.toJson(map);
    }

    private static String formatColor(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color parseColor(Object value) {
        if (value == null) return Color.BLACK;
        String hex = value.toString();
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new Color(Integer.parseInt(h, 16), false);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }
}
