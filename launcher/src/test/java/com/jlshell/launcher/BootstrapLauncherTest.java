package com.jlshell.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void promotesVerifiedPendingUpdateBeforeLaunch() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        Path jar = writeFile(tempDir.resolve("jlshell-app-0.1.42.jar"), "app-0.1.42");
        writeEntry(updates.resolve("pending.json"), "0.1.42", jar, sha256(jar), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(jar, selected);
        assertFalse(Files.exists(updates.resolve("pending.json")));
        String current = Files.readString(updates.resolve("current.json"), StandardCharsets.UTF_8);
        assertTrue(current.contains("\"version\": \"0.1.42\""));
        assertTrue(current.contains("\"startupConfirmed\": false"));
    }

    @Test
    void rollsBackUnconfirmedCurrentToPrevious() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        Path broken = writeFile(tempDir.resolve("broken.jar"), "broken");
        Path previous = writeFile(tempDir.resolve("jlshell-app-0.1.41.jar"), "app-0.1.41");
        writeEntry(updates.resolve("current.json"), "0.1.42", broken, sha256(broken), false);
        writeEntry(updates.resolve("previous.json"), "0.1.41", previous, sha256(previous), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(previous, selected);
        assertFalse(Files.exists(updates.resolve("previous.json")));
        String current = Files.readString(updates.resolve("current.json"), StandardCharsets.UTF_8);
        assertTrue(current.contains("\"version\": \"0.1.41\""));
        assertTrue(current.contains("\"startupConfirmed\": true"));
    }

    @Test
    void fallsBackToBundledJarWhenNoVerifiedUpdateExists() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        Path missing = tempDir.resolve("missing.jar");
        writeEntry(updates.resolve("current.json"), "0.1.42", missing, "bad-sha", true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(bundled, selected);
    }

    private static Path writeFile(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static void writeEntry(Path path, String version, Path jar, String sha256, boolean confirmed) throws Exception {
        String json = "{\n"
                + "  \"version\": \"" + version + "\",\n"
                + "  \"jarPath\": \"" + jar.toAbsolutePath().toString().replace("\\", "\\\\") + "\",\n"
                + "  \"sha256\": \"" + sha256 + "\",\n"
                + "  \"startupConfirmed\": " + confirmed + "\n"
                + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }
}
