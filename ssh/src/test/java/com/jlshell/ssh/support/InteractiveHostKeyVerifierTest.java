package com.jlshell.ssh.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.jlshell.ssh.support.HostKeyConfirmationCallback.TrustDecision.*;

class InteractiveHostKeyVerifierTest {
    @TempDir Path temporary;

    private PublicKey key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }

    private HostKeyConfirmationCallback answer(HostKeyConfirmationCallback.TrustDecision result, AtomicBoolean mismatch) {
        return new HostKeyConfirmationCallback() {
            public boolean confirm(String host, int port, String type, String fingerprint, boolean changed) {
                throw new AssertionError("Verifier must request the full trust decision");
            }
            public TrustDecision confirmDecision(String host, int port, String type, String fingerprint, boolean changed) {
                assertEquals("test.example", host); assertEquals(2222, port);
                mismatch.set(changed); return result;
            }
        };
    }

    @Test void trustOnceAndRejectDoNotWriteKnownHosts() throws Exception {
        Path file = temporary.resolve("known_hosts"); Files.createFile(file);
        var mismatch = new AtomicBoolean(); PublicKey key = key();
        assertTrue(new InteractiveHostKeyVerifier(file.toFile(), answer(TRUST_ONCE, mismatch)).verify("test.example", 2222, key));
        assertFalse(mismatch.get()); assertEquals("", Files.readString(file));
        assertFalse(new InteractiveHostKeyVerifier(file.toFile(), answer(REJECT, mismatch)).verify("test.example", 2222, key));
        assertEquals("", Files.readString(file));
    }

    @Test void persistentTrustIsSavedAndChangedKeyCanBeAcceptedOnceWithoutChangingFile() throws Exception {
        Path file = temporary.resolve("known_hosts"); Files.createFile(file);
        var mismatch = new AtomicBoolean(); PublicKey original = key();
        assertTrue(new InteractiveHostKeyVerifier(file.toFile(), answer(TRUST_AND_SAVE, mismatch)).verify("test.example", 2222, original));
        String saved = Files.readString(file); assertFalse(saved.isBlank());
        assertTrue(new InteractiveHostKeyVerifier(file.toFile(), (h,p,t,f,c) -> { fail("Saved key must not prompt again"); return false; })
                .verify("test.example", 2222, original));
        assertTrue(new InteractiveHostKeyVerifier(file.toFile(), answer(TRUST_ONCE, mismatch)).verify("test.example", 2222, key()));
        assertTrue(mismatch.get()); assertEquals(saved, Files.readString(file));
    }

    @Test void existingBooleanCallbacksStillPersistAcceptance() throws Exception {
        Path file = temporary.resolve("known_hosts"); Files.createFile(file);
        assertTrue(new InteractiveHostKeyVerifier(file.toFile(), (h,p,t,f,c) -> true).verify("test.example", 2222, key()));
        assertFalse(Files.readString(file).isBlank());
    }
}
