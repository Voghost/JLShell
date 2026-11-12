package com.jlshell.launcher;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Loads a verified, reusable snapshot so updates can replace the source archive safely. */
final class ApplicationRuntime implements AutoCloseable {
    private final Path archive;
    private final URLClassLoader loader;
    private final Thread shutdown;
    private boolean closed;

    private ApplicationRuntime(Path archive, URLClassLoader loader) {
        this.archive = archive; this.loader = loader;
        shutdown = new Thread(this::close, "jlshell-runtime-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    static synchronized ApplicationRuntime open(Path source, Path cache) throws Exception {
        Files.createDirectories(cache);
        removeStoppedRuntimes(cache);
        String expected = digest(source);
        Path archive = cache.resolve(expected + ".jar");
        // Serializes cache creation across launches. Readers only see a fully verified archive.
        try (var channel = java.nio.channels.FileChannel.open(cache.resolve("prepare.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || !expected.equals(digest(archive))) {
                Path pending = Files.createTempFile(cache, "prepare-", ".jar");
                try {
                    Files.copy(source, pending, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (!expected.equals(digest(pending))) {
                        throw new IOException("Application JAR changed while preparing runtime: " + source);
                    }
                    try {
                        Files.move(pending, archive, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(pending, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(pending); }
            }
        }
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{archive.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        return new ApplicationRuntime(archive, loader);
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
        // The verified content-addressed archive is reused by subsequent launches.
    }
}
