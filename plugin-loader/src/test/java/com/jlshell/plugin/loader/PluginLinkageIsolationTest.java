package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** A missing legacy UI dependency must not prevent unrelated plugins from loading. */
class PluginLinkageIsolationTest {
    @TempDir Path directory;

    @Test
    void skipsPluginWithMissingUiClassesAndLoadsNextJar() throws Exception {
        writePlugin("a-incompatible.jar", IncompatiblePlugin.class, "example.incompatible");
        writePlugin("z-working.jar", WorkingPlugin.class, "example.working");
        PluginManager manager = new PluginManager(directory.toString(), "1.0.0");
        try {
            assertThat(manager.getAvailablePlugins()).extracting(PluginDescriptor::id)
                    .contains("example.working").doesNotContain("example.incompatible");
        } finally {
            manager.deactivateAll();
        }
    }

    private void writePlugin(String filename, Class<?> entrypoint, String id) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("JLShell-Plugin-Id", id);
        attributes.putValue("JLShell-Plugin-Version", "1.0.0");
        attributes.putValue("JLShell-Plugin-Scope", "SESSION");
        String descriptor = """
                {"schemaVersion":1,"id":"%s","version":"1.0.0","scope":"SESSION",
                 "entrypoint":"%s","displayName":"Isolation test","description":"Test", "author":"Test"}
                """.formatted(id, entrypoint.getName());
        try (var jar = new JarOutputStream(Files.newOutputStream(directory.resolve(filename)), manifest)) {
            writeEntry(jar, "META-INF/jlshell-plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            writeEntry(jar, "META-INF/services/com.jlshell.plugin.api.JlShellPlugin",
                    entrypoint.getName().getBytes(StandardCharsets.UTF_8));
            String classPath = entrypoint.getName().replace('.', '/') + ".class";
            try (var input = entrypoint.getClassLoader().getResourceAsStream(classPath)) {
                writeEntry(jar, classPath, input.readAllBytes());
            }
        }
    }

    private void writeEntry(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    public static class WorkingPlugin implements JlShellPlugin {
        public String id() { return "example.working"; }
        public String displayName() { return "Working"; }
        public String version() { return "1.0.0"; }
        public String description() { return "Test"; }
        public boolean requiresSshSession() { return false; }
        public void activate(PluginContext context) { }
        public void deactivate() { }
    }

    public static class IncompatiblePlugin extends WorkingPlugin {
        public IncompatiblePlugin() {
            throw new NoClassDefFoundError("javafx/scene/Node");
        }
    }
}
