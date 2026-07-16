package com.jlshell.program.api;

/** 面向 Program API 的稳定命令执行结果。 */
public record ProgramCommandResult(String stdout, String stderr, Integer exitCode) {
}
