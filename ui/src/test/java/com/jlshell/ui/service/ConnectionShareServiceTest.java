package com.jlshell.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.ui.model.ConnectionFormData;
import org.junit.jupiter.api.Test;

class ConnectionShareServiceTest {

    private final ConnectionShareService service = new ConnectionShareService();

    @Test
    void passwordConnectionRoundTripsWithEmbeddedShareCode() {
        ConnectionFormData source = form(AuthenticationType.PASSWORD, "secret", "", "");

        String text = service.exportShareText(source, "code-123", true);
        ConnectionFormData imported = service.importShareText(text, "project-1");

        assertTrue(text.startsWith(ConnectionShareService.PREFIX));
        assertTrue(text.contains(ConnectionShareService.SHARE_CODE_SEPARATOR));
        assertFalse(service.requiresShareCode(text));
        assertEquals("web", imported.displayName());
        assertEquals("192.168.1.10", imported.host());
        assertEquals("deploy", imported.username());
        assertEquals("secret", imported.password());
        assertEquals("project-1", imported.projectId());
        assertEquals(ConnectionType.SSH, imported.connectionType());
    }

    @Test
    void shareTextCanBeParsedWithInstructionHeader() {
        ConnectionFormData source = form(AuthenticationType.PASSWORD, "secret", "", "");
        String text = "IP: 192.168.1.10\nOpen JLShell and paste this share text:\n"
                + service.exportShareText(source, "code-123", true);

        ConnectionFormData imported = service.importShareText(text, "project-1");

        assertFalse(service.requiresShareCode(text));
        assertEquals("secret", imported.password());
    }

    @Test
    void passwordConnectionWithoutEmbeddedShareCodeRequiresManualCode() {
        ConnectionFormData source = form(AuthenticationType.PASSWORD, "secret", "", "");

        String text = service.exportShareText(source, "code-123", false);

        assertFalse(text.contains("secret"));
        assertFalse(text.contains(ConnectionShareService.SHARE_CODE_SEPARATOR));
        assertTrue(service.requiresShareCode(text));
        assertThrows(IllegalArgumentException.class, () -> service.importShareText(text, "project-1"));

        ConnectionFormData imported = service.importShareText(text, "code-123".toCharArray(), "project-1");
        assertEquals("secret", imported.password());
    }

    @Test
    void encryptedShareStillRoundTripsWithManualShareCode() {
        ConnectionFormData source = form(AuthenticationType.PASSWORD, "secret", "", "");

        String text = service.exportShareText(source, "code-123".toCharArray());
        ConnectionFormData imported = service.importShareText(text, "code-123".toCharArray(), "project-1");

        assertTrue(text.startsWith(ConnectionShareService.PREFIX));
        assertFalse(text.contains("secret"));
        assertTrue(service.requiresShareCode(text));
        assertEquals("web", imported.displayName());
        assertEquals("192.168.1.10", imported.host());
        assertEquals("deploy", imported.username());
        assertEquals("secret", imported.password());
        assertEquals("project-1", imported.projectId());
        assertEquals(ConnectionType.SSH, imported.connectionType());
    }

    @Test
    void privateKeyConnectionRoundTripsPassphraseAndPath() {
        ConnectionFormData source = form(AuthenticationType.PRIVATE_KEY, "", "/tmp/id_ed25519", "key-pass");

        String text = service.exportShareText(source, "code-456".toCharArray());
        ConnectionFormData imported = service.importShareText(text, "code-456".toCharArray(), null);

        assertFalse(text.contains("key-pass"));
        assertEquals(AuthenticationType.PRIVATE_KEY, imported.authenticationType());
        assertEquals("/tmp/id_ed25519", imported.privateKeyPath());
        assertEquals("key-pass", imported.passphrase());
    }

    @Test
    void wrongShareCodeFails() {
        ConnectionFormData source = form(AuthenticationType.PASSWORD, "secret", "", "");
        String text = service.exportShareText(source, "right".toCharArray());

        assertThrows(IllegalArgumentException.class,
                () -> service.importShareText(text, "wrong".toCharArray(), null));
    }

    @Test
    void invalidShareTextFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importShareText("JLSHELL_CONNECTION_SHARE_V1:not-base64", "code".toCharArray(), null));
    }

    private static ConnectionFormData form(AuthenticationType authType, String password,
                                           String privateKeyPath, String passphrase) {
        return new ConnectionFormData(
                "id-1",
                "web",
                "192.168.1.10",
                22,
                "deploy",
                authType,
                password,
                privateKeyPath,
                passphrase,
                HostKeyVerificationMode.STRICT,
                "prod",
                "/srv/app",
                true,
                "source-project",
                ConnectionType.SSH,
                "folder-1",
                null,
                null
        );
    }
}
