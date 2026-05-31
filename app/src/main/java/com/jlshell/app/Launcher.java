package com.jlshell.app;

/**
 * IDEA 直接运行时的入口。
 *
 * JVM 对继承 Application 的 main 类会做 JavaFX 模块检查，
 * 只有 module path 上的 jar 才算；classpath 上的不算。
 * 通过一个不继承 Application 的 Launcher 类间接调用，
 * 等 Application.launch() 执行时 JavaFX 已经在 classpath 上了。
 */
public class Launcher {
    public static void main(String[] args) {
        JlShellDesktopApplication.main(args);
    }
}
