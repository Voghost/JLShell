package com.jlshell.terminal.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 终端配色方案统一注册中心。
 * 合并内置方案（只读）和用户自定义方案（持久化）。
 */
public class ColorSchemeRegistry {

    private final BuiltInColorSchemeLoader builtInLoader;
    private final CustomColorSchemeStore customStore;

    private volatile List<TerminalColorScheme> builtInCache;

    public ColorSchemeRegistry(BuiltInColorSchemeLoader builtInLoader, CustomColorSchemeStore customStore) {
        this.builtInLoader = builtInLoader;
        this.customStore = customStore;
    }

    public List<TerminalColorScheme> builtInSchemes() {
        if (builtInCache == null) {
            builtInCache = builtInLoader.loadAll();
        }
        return builtInCache;
    }

    public List<TerminalColorScheme> customSchemes() {
        return customStore.listAll();
    }

    public List<TerminalColorScheme> allSchemes() {
        List<TerminalColorScheme> result = new ArrayList<>(builtInSchemes());
        result.addAll(customSchemes());
        return result;
    }

    public Optional<TerminalColorScheme> findByName(String name) {
        Optional<TerminalColorScheme> builtIn = builtInSchemes().stream()
                .filter(s -> s.name().equals(name))
                .findFirst();
        if (builtIn.isPresent()) return builtIn;
        return customStore.findByName(name);
    }

    public boolean isBuiltIn(String name) {
        return builtInSchemes().stream().anyMatch(s -> s.name().equals(name));
    }

    public TerminalColorScheme copyScheme(TerminalColorScheme source, String newName) {
        return new TerminalColorScheme(
                newName,
                source.background(), source.foreground(), source.cursorColor(),
                source.selectionBackground(), source.selectionForeground(),
                source.hyperlinkColor(), source.searchMatchBackground(), source.searchMatchForeground(),
                source.black(), source.red(), source.green(), source.yellow(),
                source.blue(), source.purple(), source.cyan(), source.white(),
                source.brightBlack(), source.brightRed(), source.brightGreen(), source.brightYellow(),
                source.brightBlue(), source.brightPurple(), source.brightCyan(), source.brightWhite(),
                source.opacity()
        );
    }

    public void saveCustomScheme(TerminalColorScheme scheme) {
        customStore.save(scheme);
    }

    public void deleteCustomScheme(String name) {
        if (isBuiltIn(name)) {
            throw new IllegalArgumentException("Cannot delete built-in color scheme: " + name);
        }
        customStore.deleteByName(name);
    }

    public void refreshCustom() {
        // Custom schemes are read from DB on each call, no cache to invalidate
    }
}
