package com.jlshell.launcher;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Owns a private application archive and its loader until the JVM shuts down. */
final class ApplicationRuntime implements AutoCloseable {
    private final Path directory;
    private final Path archive;
    private final URLClassLoader loader;
    private final Thread shutdown;
    private boolean closed;

    private ApplicationRuntime(Path directory, Path archive, URLClassLoader loader) {
        this.directory = directory; this.archive = archive; this.loader = loader;
        shutdown = new Thread(this::close, "jlshell-runtime-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    static ApplicationRuntime open(Path source, Path cache) throws Exception {
        Files.createDirectories(cache);
        removeStoppedRuntimes(cache);
        Path directory = Files.createTempDirectory(cache, "runtime-" + ProcessHandle.current().pid() + "-");
        Path archive = directory.resolve("app.jar");
        URLClassLoader loader = null;
        try {
            String expected = digest(source);
            Files.copy(source, archive);
            if (!expected.equals(digest(archive))) throw new IOException("Application JAR changed while preparing runtime: " + source);
            loader = new URLClassLoader(new java.net.URL[]{archive.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            return new ApplicationRuntime(directory, archive, loader);
        } catch (Exception | Error error) {
            if (loader != null) try { loader.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            try { Files.deleteIfExists(archive); Files.deleteIfExists(directory); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    URLClassLoader loader() { return loader; }
    Path archive() { return archive; }

    private static String digest(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = new byte[64 * 1024]; int read;
            while ((read = stream.read(bytes)) != -1) digest.update(bytes, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void removeStoppedRuntimes(Path cache) throws IOException {
        try (var directories = Files.list(cache)) {
            for (Path directory : directories.toList()) {
                String name = directory.getFileName().toString();
                if (!name.matches("runtime-[0-9]+-[0-9]+") || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    long pid = Long.parseLong(name.split("-")[1]);
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                    // Only our fixed archive and its now-empty folder; never recurse into unknown files.
                    Files.deleteIfExists(directory.resolve("app.jar")); Files.deleteIfExists(directory);
                } catch (IOException | NumberFormatException ignored) { /* best effort after a crash or halt */ }
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return; closed = true;
        if (Thread.currentThread() != shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
        try { loader.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(archive); Files.deleteIfExists(directory); } catch (IOException ignored) { }
    }
}
