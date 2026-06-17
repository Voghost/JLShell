package com.jlshell.terminal.service;

import java.util.List;
import java.util.Optional;

import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 自定义终端配色方案的持久化接口。
 * 仅存储用户自建/复制的方案，内置方案不经过此接口。
 */
public interface CustomColorSchemeStore {

    List<TerminalColorScheme> listAll();

    Optional<TerminalColorScheme> findByName(String name);

    void save(TerminalColorScheme scheme);

    void deleteByName(String name);
}
