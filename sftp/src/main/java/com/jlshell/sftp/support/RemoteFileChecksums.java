package com.jlshell.sftp.support;

import com.jlshell.sftp.service.FileChecksums;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.SFTPClient;

final class RemoteFileChecksums {
    private static final Pattern HASH = Pattern.compile("^(?:MD5\\(stdin\\)\\s*=\\s*|\\(stdin\\)\\s*=\\s*)?([0-9a-fA-F]{32})(?:\\s+-)?$");

    static String md5(SSHClient ssh, SFTPClient sftp, String path, BooleanSupplier cancelled) throws IOException {
        return md5(ssh, sftp, path, cancelled, null);
    }

    static String md5(SSHClient ssh, SFTPClient sftp, String path, BooleanSupplier cancelled,
                       com.jlshell.core.support.PerformanceTrace timing) throws IOException {
        FileChecksums.checkCancelled(cancelled);
        FileAttributes before = sftp.stat(path);
        if (before.getType() != FileMode.Type.REGULAR) throw new IOException("MD5 requires a regular file");
        Optional<String> serverHash = tryServerMd5(ssh, path, cancelled);
        String result;
        if (timing != null) timing.mark(serverHash.isPresent() ? "server-hash-used" : "sftp-read-fallback");
        if (serverHash.isPresent()) result = serverHash.get();
        else try (var file = sftp.open(path, java.util.EnumSet.of(OpenMode.READ));
                  var input = file.new ReadAheadRemoteFileInputStream(64, 0, Math.max(1, before.getSize()))) {
            result = FileChecksums.md5(input, before.getSize(), cancelled);
        }
        FileChecksums.checkCancelled(cancelled);
        FileAttributes after = sftp.stat(path);
        if (before.getSize() != after.getSize() || before.getMtime() != after.getMtime())
            throw new IOException("File changed while calculating MD5");
        return result;
    }

    private static Optional<String> tryServerMd5(SSHClient ssh, String path, BooleanSupplier cancelled) throws IOException {
        try (var session = ssh.startSession()) {
            // Keep filenames out of the SSH exec command and use a known POSIX interpreter.
            // Windows/SFTP-only hosts without sh simply fall back to streaming SFTP.
            var command = session.exec("sh -s");
            try (var input = command.getOutputStream()) {
                input.write((command(path) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
            while (command.isOpen()) {
                FileChecksums.checkCancelled(cancelled);
                if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException("Remote MD5 timed out");
                try { Thread.sleep(50); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); FileChecksums.checkCancelled(cancelled); }
            }
            FileChecksums.checkCancelled(cancelled);
            if (!Integer.valueOf(0).equals(command.getExitStatus())) return Optional.empty();
            return parse(new String(command.getInputStream().readNBytes(512), StandardCharsets.US_ASCII));
        } catch (java.net.SocketTimeoutException timeout) {
            throw timeout;
        } catch (IOException unsupportedExec) {
            FileChecksums.checkCancelled(cancelled);
            // SFTP-only accounts and servers without Unix checksum tools remain supported.
            return Optional.empty();
        }
    }

    static String command(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid file path");
        String quoted = "'" + path.replace("'", "'\\''") + "'";
        // stdin redirection handles spaces, quotes, newlines and leading '-' without shell evaluation.
        return "LC_ALL=C; export LC_ALL; p=" + quoted + "; "
                + "if [ ! -f \"$p\" ]; then exit 66; "
                + "elif command -v md5sum >/dev/null 2>&1; then md5sum < \"$p\"; "
                + "elif command -v md5 >/dev/null 2>&1; then md5 -q < \"$p\"; "
                + "elif command -v openssl >/dev/null 2>&1; then openssl dgst -md5 < \"$p\"; "
                + "else exit 127; fi";
    }

    static Optional<String> parse(String output) {
        var match = HASH.matcher(output.strip());
        return match.matches() ? Optional.of(match.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }
}
