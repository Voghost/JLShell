package com.jlshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confirms that a jar selected by the bootstrap launcher reached the JavaFX UI.
 */
final class UpdateActivationMarker {

    private UpdateActivationMarker() {}

    static void markStartupConfirmed() {
        String activeJar = System.getProperty("jlshell.active.jar", "");
        if (activeJar.isBlank()) {
            return;
        }

        Path current = Path.of(System.getProperty(
                "jlshell.update.dir",
                Path.of(System.getProperty("jlshell.app.dir", System.getProperty("user.dir", ".")), "updates").toString()
        )).resolve("current.json");
        if (!Files.isRegularFile(current)) {
            return;
        }

        try {
            String json = Files.readString(current, StandardCharsets.UTF_8);
            if (!json.contains(activeJar.replace("\\", "\\\\"))) {
                return;
            }
            String updated = json.replace("\"startupConfirmed\": false", "\"startupConfirmed\": true");
            if (!updated.equals(json)) {
                Files.writeString(current, updated, StandardCharsets.UTF_8);
            }
            // Keep the previous installed JAR for recovery until the next update.
        } catch (IOException ignored) {
            // Startup confirmation is best-effort; failure must not block the app.
        }
    }

}
