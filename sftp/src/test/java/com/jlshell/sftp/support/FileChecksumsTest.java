package com.jlshell.sftp.support;

import com.jlshell.sftp.exception.TransferCancelledException;
import com.jlshell.sftp.service.FileChecksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileChecksumsTest {
    @TempDir Path temporary;

    @Test void hashesEmptyAndNonemptyLocalFilesAndRejectsDirectories() throws Exception {
        Path file = temporary.resolve("中文 file.txt");
        Files.writeString(file, "abc");
        assertEquals("900150983cd24fb0d6963f7d28e17f72", FileChecksums.localMd5(file, () -> false));
        Files.writeString(file, "");
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileChecksums.localMd5(file, () -> false));
        assertThrows(IOException.class, () -> FileChecksums.localMd5(temporary, () -> false));
    }

    @Test void cancellationAndUnexpectedLengthDoNotProduceAValidDigest() {
        byte[] data = new byte[200_000];
        var checks = new AtomicInteger();
        assertThrows(TransferCancelledException.class, () -> FileChecksums.md5(new ByteArrayInputStream(data),
                data.length, () -> checks.incrementAndGet() >= 3));
        assertThrows(IOException.class, () -> FileChecksums.md5(new ByteArrayInputStream(data), data.length - 1, () -> false));
        assertThrows(IOException.class, () -> FileChecksums.md5(new ByteArrayInputStream(data), data.length + 1, () -> false));
    }

    @Test void parsesOnlyKnownChecksumOutputFormats() {
        String hash = "900150983cd24fb0d6963f7d28e17f72";
        for (String output : new String[]{hash + "  -\n", hash.toUpperCase(), "MD5(stdin)= " + hash, "(stdin)= " + hash})
            assertEquals(hash, RemoteFileChecksums.parse(output).orElseThrow());
        assertTrue(RemoteFileChecksums.parse("some error " + hash).isEmpty());
        assertTrue(RemoteFileChecksums.parse(hash + "\n" + hash).isEmpty());
        assertTrue(RemoteFileChecksums.parse("not a hash").isEmpty());
    }

    @Test @EnabledOnOs({OS.MAC, OS.LINUX})
    void remoteShellCommandTreatsMetacharactersAndNewlinesAsFilenameData() throws Exception {
        for (String name : new String[]{"ordinary", "-option", "a'b\"c", "中文 $(touch SHOULD_NOT_EXIST); `touch BAD`", "a\nb"}) {
            Path file = Files.writeString(temporary.resolve(name), "abc");
            Process process = new ProcessBuilder("/bin/sh", "-c", RemoteFileChecksums.command(file.toString()))
                    .directory(temporary.toFile()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("900150983cd24fb0d6963f7d28e17f72", RemoteFileChecksums.parse(
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).orElseThrow());
        }
        assertFalse(Files.exists(temporary.resolve("SHOULD_NOT_EXIST")));
        assertFalse(Files.exists(temporary.resolve("BAD")));
    }
}
