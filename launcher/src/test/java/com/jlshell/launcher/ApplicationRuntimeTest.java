package com.jlshell.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationRuntimeTest {
    @TempDir Path temporary;

    @Test void loadsLazyClassesAndResourcesAfterMainReturnsAndOriginalArchiveIsReplaced() throws Exception {
        Path classes = temporary.resolve("classes"); Files.createDirectories(classes);
        Path source = temporary.resolve("Main.java");
        Files.writeString(source, "package fixture; public class Main { public static void main(String[] args) {} }");
        Path late = temporary.resolve("Late.java");
        Files.writeString(late, "package fixture; public class Late { public static String value() { return \"loaded later\"; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString(), late.toString()));
        Path original = temporary.resolve("app.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(original))) {
            for (String name : new String[]{"fixture/Main.class", "fixture/Late.class"}) {
                jar.putNextEntry(new JarEntry(name)); jar.write(Files.readAllBytes(classes.resolve(name))); jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("fixture.txt")); jar.write("original resource".getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
        }
        Path snapshot;
        try (ApplicationRuntime runtime = ApplicationRuntime.open(original, temporary.resolve("cache"))) {
            snapshot = runtime.archive();
            Class.forName("fixture.Main", true, runtime.loader()).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            // Simulates a preview rebuild or installer replacing the original app JAR.
            try (var jar = new JarOutputStream(Files.newOutputStream(original))) { jar.putNextEntry(new JarEntry("replacement.txt")); jar.closeEntry(); }
            assertEquals("loaded later", Class.forName("fixture.Late", true, runtime.loader()).getMethod("value").invoke(null));
            try (var stream = runtime.loader().getResourceAsStream("fixture.txt")) {
                assertNotNull(stream); assertEquals("original resource", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            assertTrue(Files.exists(snapshot));
        }
        assertFalse(Files.exists(snapshot), "Explicit close must release the private archive");
    }

    @Test void cleansOnlyStoppedRuntimeArchivesAndLeavesRunningOrUnknownFilesAlone() throws Exception {
        Path cache = temporary.resolve("cache"); Files.createDirectories(cache);
        Path stale = Files.createDirectory(cache.resolve("runtime-999999999-1")); Files.writeString(stale.resolve("app.jar"), "stale");
        Path live = Files.createDirectory(cache.resolve("runtime-" + ProcessHandle.current().pid() + "-1")); Files.writeString(live.resolve("app.jar"), "live");
        Path unrelated = Files.createDirectory(cache.resolve("other")); Files.writeString(unrelated.resolve("app.jar"), "untouched");
        Path source = temporary.resolve("app.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(source))) { }
        try (var runtime = ApplicationRuntime.open(source, cache)) {
            assertFalse(Files.exists(stale)); assertTrue(Files.exists(live.resolve("app.jar"))); assertTrue(Files.exists(unrelated.resolve("app.jar")));
        }
    }
}
