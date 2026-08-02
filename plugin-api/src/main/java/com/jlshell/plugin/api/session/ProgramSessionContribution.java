package com.jlshell.plugin.api.session;

import java.util.Locale;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;

/**
 * Program 插件提供的会话级功能入口。
 *
 * <p>贡献仍属于 Program 插件本身，不是独立 Session 插件，也不需要第二个
 * ServiceLoader 入口或安装包。</p>
 */
public interface ProgramSessionContribution {

    String displayName();

    default String displayName(Locale locale) {
        return displayName();
    }

    String description();

    default String description(Locale locale) {
        return description();
    }

    default boolean supports(SshSessionContext session) {
        return session != null;
    }

    /**
     * 激活当前 SSH 会话中的贡献。
     *
     * @return 会话控制器；宿主保证在会话或 Program 插件结束时调用 close
     */
    ProgramSessionController activate(PluginContext context);
}
