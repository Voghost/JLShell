package com.jlshell.app;

/** Executed by packaging CI using the bundled runtime and the curated application JAR. */
public final class DistributionSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("jlshell-distribution-smoke-").toString());
        if (DistributionSmoke.class.getClassLoader().getResource("javafx/application/Application.class") != null
                || ModuleLayer.boot().modules().stream().anyMatch(module -> module.getName().startsWith("javafx.")))
            throw new IllegalStateException("Distribution contains unexpected JavaFX runtime classes");
        Class.forName("com.jlshell.ui.swing.SwingWorkbench");
        Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
        if (com.sun.jna.Native.POINTER_SIZE != 8) throw new IllegalStateException("Expected 64-bit native runtime");
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:"); var statement = db.createStatement()) {
            statement.execute("create table packaging_check (value text)");
        }
        String[] command = System.getProperty("os.name").startsWith("Windows")
                ? new String[]{"cmd.exe", "/c", "exit", "0"} : new String[]{"/bin/sh", "-c", "exit 0"};
        var process = new com.pty4j.PtyProcessBuilder().setCommand(command).start();
        try {
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException("Native terminal PTY smoke failed");
        } finally { process.destroyForcibly(); }
        System.out.println("Distribution smoke passed: Swing-only UI, JDBC/SQLite, SSH crypto, JNA, native PTY");
    }
}
