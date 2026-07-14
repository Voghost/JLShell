package com.jlshell.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsWindowsRelaunchCommandUsingBundledJava() {
        Path java = Path.of("D:/Program Files/JLShell/runtime/bin/javaw.exe");
        Path launcher = Path.of("D:/Program Files/JLShell/app/jlshell-launcher.jar");

        List<String> command = BootstrapLauncher.windowsRelaunchCommand(
                java, launcher, new String[] { "--open", "host-a" });

        assertEquals(java.toString(), command.get(0));
        assertTrue(command.contains("-Djlshell.runtime.isolated=true"));
        assertEquals("-jar", command.get(7));
        assertEquals(launcher.toString(), command.get(8));
        assertEquals(List.of("--open", "host-a"), command.subList(9, 11));
    }

    @Test
    void resolvesJpackageApplicationRootFromAppDirectory() {
        Path root = tempDir.resolve("JLShell");

        assertEquals(root.toAbsolutePath().normalize(),
                BootstrapLauncher.applicationRootDir(root.resolve("app")));
        assertEquals(root.toAbsolutePath().normalize(),
                BootstrapLauncher.applicationRootDir(root));
    }

    @Test
    void sanitizesExternalJavaEnvironmentForWindowsChild() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Path", "C:\\Program Files\\Java\\jdk1.8.0_131\\jre\\bin;C:\\tools");
        environment.put("Java_Home", "C:\\Program Files\\Java\\jdk1.8.0_131");
        environment.put("JAVA_TOOL_OPTIONS", "-Djava.library.path=C:\\old-java");
        environment.put("_JAVA_OPTIONS", "-Xmx2g");
        environment.put("JDK_JAVA_OPTIONS", "--add-modules=ALL-SYSTEM");
        environment.put("SystemRoot", "C:\\Windows");
        environment.put("APPDATA", "C:\\Users\\tester\\AppData\\Roaming");
        Path runtime = Path.of("D:/Program Files/JLShell/runtime");

        BootstrapLauncher.sanitizeWindowsEnvironment(environment, runtime);

        assertEquals(runtime.toString(), environment.get("JAVA_HOME"));
        assertEquals(runtime.resolve("bin") + ";C:\\Windows\\System32;C:\\Windows;C:\\tools",
                environment.get("PATH"));
        assertFalse(environment.containsKey("Path"));
        assertFalse(environment.containsKey("Java_Home"));
        assertFalse(environment.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(environment.containsKey("_JAVA_OPTIONS"));
        assertFalse(environment.containsKey("JDK_JAVA_OPTIONS"));
        assertEquals("C:\\Users\\tester\\AppData\\Roaming", environment.get("APPDATA"));
    }

    @Test
    void copiesVerifiedPendingUpdateToBundledJarWhenInstallDirIsWritable() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        Path jar = writeFile(tempDir.resolve("jlshell-app-0.1.42.jar"), "app-0.1.42");
        writeEntry(updates.resolve("pending.json"), "0.1.42", jar, sha256(jar), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(bundled, selected);
        assertEquals("app-0.1.42", Files.readString(bundled, StandardCharsets.UTF_8));
        assertEquals("bundled", Files.readString(tempDir.resolve("bundled.jar.previous"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(updates.resolve("pending.json")));
        String current = Files.readString(updates.resolve("current.json"), StandardCharsets.UTF_8);
        assertTrue(current.contains("\"version\": \"0.1.42\""));
        assertTrue(current.contains(bundled.toAbsolutePath().toString().replace("\\", "\\\\")));
        assertTrue(current.contains("\"startupConfirmed\": false"));
    }

    @Test
    void loadsPendingUpdateEvenWhenInstallDirIsMissing() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = tempDir.resolve("missing-dir").resolve("bundled.jar");
        Path jar = writeFile(tempDir.resolve("jlshell-app-0.1.42.jar"), "app-0.1.42");
        writeEntry(updates.resolve("pending.json"), "0.1.42", jar, sha256(jar), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(jar, selected);
        assertFalse(Files.exists(updates.resolve("pending.json")));
        String current = Files.readString(updates.resolve("current.json"), StandardCharsets.UTF_8);
        assertTrue(current.contains(jar.toAbsolutePath().toString().replace("\\", "\\\\")));
        assertTrue(current.contains("\"startupConfirmed\": false"));
    }

    @Test
    void keepsBundledJarWhenNoVerifiedUpdateExists() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        Path backup = writeFile(tempDir.resolve("bundled.jar.previous"), "previous");

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(bundled, selected);
        assertEquals("bundled", Files.readString(bundled, StandardCharsets.UTF_8));
        assertEquals("previous", Files.readString(backup, StandardCharsets.UTF_8));
    }

    @Test
    void rollsBackUnconfirmedCurrentToPrevious() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled");
        writeFile(tempDir.resolve("bundled.jar.previous"), "bundled-previous");
        Path broken = writeFile(tempDir.resolve("broken.jar"), "broken");
        Path previous = writeFile(tempDir.resolve("jlshell-app-0.1.41.jar"), "app-0.1.41");
        writeEntry(updates.resolve("current.json"), "0.1.42", broken, sha256(broken), false);
        writeEntry(updates.resolve("previous.json"), "0.1.41", previous, sha256(previous), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled);

        assertEquals(previous, selected);
        assertEquals("bundled-previous", Files.readString(bundled, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempDir.resolve("bundled.jar.previous")));
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

    @Test
    void keepsNewerInstalledJarWhenCachedCurrentUpdateIsOlder() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled-0.1.54");
        writeFile(tempDir.resolve("bundled.jar.previous"), "bundled-0.1.52");
        Path cachedJar = writeFile(tempDir.resolve("jlshell-app-0.1.53.jar"), "cached-0.1.53");
        Path previousJar = writeFile(tempDir.resolve("jlshell-app-0.1.52.jar"), "previous-0.1.52");
        writeEntry(updates.resolve("current.json"), "0.1.53", cachedJar, sha256(cachedJar), false);
        writeEntry(updates.resolve("previous.json"), "0.1.52", previousJar, sha256(previousJar), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled, "0.1.54");

        assertEquals(bundled, selected);
        assertEquals("bundled-0.1.54", Files.readString(bundled, StandardCharsets.UTF_8));
        assertFalse(Files.exists(updates.resolve("current.json")));
        assertFalse(Files.exists(tempDir.resolve("bundled.jar.previous")));
    }

    @Test
    void doesNotPromoteCachedUpdateWhenItIsNotNewerThanInstalledJar() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled-0.1.54");
        Path cachedJar = writeFile(tempDir.resolve("jlshell-app-0.1.54.jar"), "cached-0.1.54");
        writeEntry(updates.resolve("pending.json"), "0.1.54", cachedJar, sha256(cachedJar), false);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled, "0.1.54");

        assertEquals(bundled, selected);
        assertEquals("bundled-0.1.54", Files.readString(bundled, StandardCharsets.UTF_8));
        assertFalse(Files.exists(updates.resolve("pending.json")));
    }

    @Test
    void usesCachedUpdateWhenItIsNewerThanInstalledJar() throws Exception {
        Path updates = Files.createDirectories(tempDir.resolve("updates"));
        Path bundled = writeFile(tempDir.resolve("bundled.jar"), "bundled-0.1.54");
        Path cachedJar = writeFile(tempDir.resolve("jlshell-app-0.1.55.jar"), "cached-0.1.55");
        writeEntry(updates.resolve("current.json"), "0.1.55", cachedJar, sha256(cachedJar), true);

        Path selected = BootstrapLauncher.selectApplicationJar(updates, bundled, "0.1.54");

        assertEquals(cachedJar, selected);
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
