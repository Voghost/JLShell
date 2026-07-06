package com.jlshell.core.shortcut;

/**
 * 可配置快捷键的定义。
 * 每个定义包含唯一标识、分类、i18n 键和默认快捷键。
 */
public record ShortcutDefinition(
    String id,                // 唯一标识，如 "app.newConnection", "terminal.copy"
    String category,          // 分类："app" 或 "terminal"
    String nameKey,           // i18n key，如 "shortcut.app.newConnection"
    String defaultPrimary,    // 默认主快捷键，如 "meta N"；null 表示无默认
    String defaultSecondary   // 默认备用快捷键；null 表示无默认
) {}
