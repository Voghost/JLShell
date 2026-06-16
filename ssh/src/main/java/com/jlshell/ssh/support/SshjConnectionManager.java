package com.jlshell.ssh.support;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.jlshell.core.exception.ConnectionException;
import com.jlshell.core.model.AuthenticationMethod;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.model.SessionId;
import com.jlshell.core.security.CredentialPayload;
import com.jlshell.core.service.ConnectionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ssh.support.session.SshjSession;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 SSHJ 的 ConnectionManager 实现。
 * 负责把 core 层抽象请求转换成真实的 SSH 建连和认证流程。
 */
public class SshjConnectionManager implements ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SshjConnectionManager.class);

    private final ExecutorService executorService;
    private final EphemeralTrustHostKeyVerifier ephemeralTrustHostKeyVerifier;
    private final HostKeyConfirmationCallback hostKeyConfirmationCallback;

    public SshjConnectionManager(
            ExecutorService executorService,
            EphemeralTrustHostKeyVerifier ephemeralTrustHostKeyVerifier,
            HostKeyConfirmationCallback hostKeyConfirmationCallback
    ) {
        this.executorService = executorService;
        this.ephemeralTrustHostKeyVerifier = ephemeralTrustHostKeyVerifier;
        this.hostKeyConfirmationCallback = hostKeyConfirmationCallback;
    }

    @Override
    public CompletableFuture<SshSession> connect(ConnectionRequest request) {
        // 所有网络连接都放到后台线程池，避免阻塞 JavaFX UI 线程。
        return CompletableFuture.supplyAsync(() -> connectBlocking(request), executorService);
    }

    private SshSession connectBlocking(ConnectionRequest request) {
        SSHClient client = new SSHClient();
        try {
            configureHostKeyVerification(client, request.hostKeyVerificationMode());
            client.setConnectTimeout(Math.toIntExact(request.target().connectTimeout().toMillis()));
            // shell 连接不设 socket read timeout（设为 0），避免长时间无输出时误断。
            // 断连检测依赖 keepalive 机制（认证完成后设置）。
            client.setTimeout(0);
            client.connect(request.target().host(), request.target().port());
            authenticate(client, request);

            // 认证完成后调大窗口以提升 SFTP 传输吞吐
            client.getConnection().setWindowSize(2 * 1024 * 1024);
            client.getConnection().setMaxPacketSize(64 * 1024);

            // keepalive 必须在认证完成后设置，否则会破坏 strict KEX 握手顺序。
            // 每 60 秒发一次，服务端无响应时 transport 抛异常，让 JediTerm 读取线程感知断连。
            client.getConnection().getKeepAlive().setKeepAliveInterval(60);

            log.info("SSH session established for {}@{}:{}", request.target().username(),
                    request.target().host(), request.target().port());

            return new SshjSession(
                    SessionId.randomId(),
                    request.displayName(),
                    request.target(),
                    client,
                    executorService
            );
        } catch (Exception exception) {
            closeQuietly(client);
            throw new ConnectionException("Failed to establish SSH connection for " + request.displayName(), exception);
        } finally {
            request.credential().clear();
        }
    }

    private void configureHostKeyVerification(SSHClient client, HostKeyVerificationMode mode) throws IOException {
        if (mode == HostKeyVerificationMode.STRICT) {
            File sshDir = OpenSSHKnownHosts.detectSSHDir();
            File knownHosts;
            if (sshDir != null) {
                knownHosts = new File(sshDir, "known_hosts");
            } else {
                knownHosts = new File(System.getProperty("user.home"), ".ssh/known_hosts");
            }
            // Ensure the file exists so OpenSSHKnownHosts can read it.
            if (!knownHosts.isFile()) {
                File parent = knownHosts.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                knownHosts.createNewFile();
            }
            client.addHostKeyVerifier(new InteractiveHostKeyVerifier(knownHosts, hostKeyConfirmationCallback));
            return;
        }
        if (mode == HostKeyVerificationMode.ACCEPT_ONCE) {
            // 首次连接临时接受，适合测试环境或未持久化 known_hosts 的早期版本。
            client.addHostKeyVerifier(ephemeralTrustHostKeyVerifier);
            return;
        }
        // 明确标记为不安全模式，仅用于开发调试。
        client.addHostKeyVerifier(new PromiscuousVerifier());
    }

    private void authenticate(SSHClient client, ConnectionRequest request) throws IOException {
        if (request.authenticationMethod() == AuthenticationMethod.PASSWORD) {
            client.authPassword(request.target().username(), String.valueOf(request.credential().secret()));
            return;
        }

        CredentialPayload cred = request.credential();
        if (cred.privateKeyPath() != null) {
            KeyProvider keyProvider = client.loadKeys(
                    cred.privateKeyPath().toString(),
                    emptyToNull(cred.secret())
            );
            client.authPublickey(request.target().username(), keyProvider);
        } else if (cred.privateKeyContent() != null) {
            OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
            String passphrase = emptyToNull(cred.secret());
            keyFile.init(new StringReader(new String(cred.privateKeyContent(), StandardCharsets.UTF_8)),
                    passphrase != null ? PasswordUtils.createOneOff(passphrase.toCharArray()) : null);
            client.authPublickey(request.target().username(), keyFile);
        }
    }

    private String emptyToNull(char[] value) {
        return value.length == 0 ? null : String.valueOf(value);
    }

    private void closeQuietly(SSHClient client) {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            log.debug("Ignoring SSH disconnect failure", ignored);
        }
        try {
            client.close();
        } catch (IOException ignored) {
            log.debug("Ignoring SSH close failure", ignored);
        }
    }
}
