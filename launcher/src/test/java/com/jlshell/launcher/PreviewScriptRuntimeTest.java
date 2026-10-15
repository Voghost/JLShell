package com.jlshell.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/** Run the real preview script and replace the build outputs while its JVM remains alive. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class PreviewScriptRuntimeTest {
    @TempDir Path temporary;

    @Test void previewSnapshotKeepsLazyClassesAndPluginsLoadableDuringTheNextBuild() throws Exception {
        for (boolean isolated : new boolean[]{false, true}) {
            Path repository = temporary.resolve(isolated ? "isolated" : "direct");
            Path ui = Files.createDirectories(repository.resolve("ui"));
            Files.copy(Path.of("../ui/run-preview.sh"), ui.resolve("run-preview.sh"));
            Path classes = Files.createDirectories(repository.resolve("classes"));
            Path main = repository.resolve("Main.java"), late = repository.resolve("Late.java");
            Files.writeString(main, """
                    package fixture;
                    import java.nio.file.*;
                    public class Main {
                        public static void main(String[] args) throws Exception {
                            Path home = Path.of(System.getProperty("user.home"));
                            Files.writeString(home.resolve("ready.tmp"), Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                            Files.move(home.resolve("ready.tmp"), home.resolve("ready"), StandardCopyOption.ATOMIC_MOVE);
                            long deadline = System.nanoTime() + 15_000_000_000L;
                            while (!Files.exists(home.resolve("release"))) {
                                if (System.nanoTime() > deadline) throw new AssertionError("No release signal");
                                Thread.sleep(20);
                            }
                            String value = (String) Class.forName("fixture.Late").getMethod("value").invoke(null);
                            if (!"late class loaded".equals(value)) throw new AssertionError(value);
                            String plugin = Files.readString(Path.of(System.getProperty("jlshell.preview.plugins"), "sysmon.jar"));
                            if (!"original plugin".equals(plugin)) throw new AssertionError("Plugin archive was overwritten");
                            Files.writeString(home.resolve("loaded"), value);
                        }
                    }
                    """);
            Files.writeString(late, "package fixture; public class Late { public static String value() { return \"late class loaded\"; } }");
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), main.toString(), late.toString()));
            Path archive = Files.createDirectories(repository.resolve("app/target")).resolve("jlshell-app-test.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "fixture.Main");
            try (var jar = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
                for (String entry : new String[]{"fixture/Main.class", "fixture/Late.class"}) {
                    jar.putNextEntry(new JarEntry(entry)); jar.write(Files.readAllBytes(classes.resolve(entry))); jar.closeEntry();
                }
            }
            Path plugin = Files.createDirectories(repository.resolve("plugins/plugin-sysmon/target")).resolve("plugin-sysmon-0.1.0.RELEASE-fat.jar");
            Files.writeString(plugin, "original plugin");
            Path home = Files.createDirectories(repository.resolve("home")), log = repository.resolve("run.log");
            ProcessBuilder builder = isolated
                    ? new ProcessBuilder("/usr/bin/env", "bash", ui.resolve("run-preview.sh").toString(), "--no-build")
                    : new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Duser.home=" + home,
                            "-Djlshell.preview.plugins=" + plugin.getParent(), "-jar", archive.toString());
            // The direct baseline fails at class loading, before it needs to read the plugin fixture.
            builder.environment().put("JLSHELL_PREVIEW_JAVA_HOME", System.getProperty("java.home"));
            builder.environment().put("JLSHELL_PREVIEW_HOME", home.toString());
            Process child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(home.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(home.resolve("ready")), () -> read(log));
                Path runtime = Path.of(Files.readString(home.resolve("ready")));
                assertEquals(isolated, !Files.isSameFile(runtime, archive));
                // Truncate the same build-output files, matching Maven package while a preview is open.
                try (var jar = new JarOutputStream(Files.newOutputStream(archive))) { jar.putNextEntry(new JarEntry("replacement")); jar.closeEntry(); }
                Files.writeString(plugin, "replacement plugin");
                Files.writeString(home.resolve("release"), "go");
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), () -> read(log));
                if (isolated) {
                    assertEquals(0, child.exitValue(), () -> read(log));
                    assertEquals("late class loaded", Files.readString(home.resolve("loaded")));
                    assertFalse(Files.exists(runtime), () -> "The private snapshot must be cleaned after its JVM exits: " + read(log));
                } else {
                    assertNotEquals(0, child.exitValue());
                    assertTrue(read(log).contains("ClassNotFoundException"), () -> read(log));
                }
            } finally {
                child.descendants().forEach(process -> process.destroyForcibly());
                if (child.isAlive()) child.destroyForcibly();
            }
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }
}
