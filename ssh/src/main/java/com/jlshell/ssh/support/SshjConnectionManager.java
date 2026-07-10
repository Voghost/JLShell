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
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 30;
    private static final int SOCKET_TIMEOUT_MULTIPLIER = 3;

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
            configureSocketTimeout(client);
            client.connect(request.target().host(), request.target().port());
            authenticate(client, request);
            startTransportKeepAlive(client);

            // 认证完成后调大窗口以提升 SFTP 传输吞吐
            // 注：windowSize 默认已是 2MB，显式设置确保一致；
            // maxPacketSize 保持默认 32KB（SFTP 协议单消息上限），不要超过否则部分服务器会 EOF
            client.getConnection().setWindowSize(2 * 1024 * 1024);

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

    /**
     * Socket timeout 必须在 {@link SSHClient#connect(String, int)} 前设置，SSHJ 只会在创建
     * socket 时把该值应用到 {@code SO_TIMEOUT}。
     */
    static void configureSocketTimeout(SSHClient client) {
        int socketTimeoutMillis = KEEP_ALIVE_INTERVAL_SECONDS * SOCKET_TIMEOUT_MULTIPLIER * 1000;
        client.setTimeout(socketTimeoutMillis);
        log.info("SSH socket timeout configured before connect: {}s", socketTimeoutMillis / 1000);
    }

    /**
     * SSHJ 会在 {@code connect()} 内立即启动已启用的保活线程。严格 KEX 服务器（例如
     * OpenSSH 9.2）要求 KEXINIT 是握手中的首个 SSH 包，因此不能在 connect 前启用保活。
     * 认证成功后 transport 的活动 service 已是 ssh-connection，此时再启动线程才安全。
     */
    static void startTransportKeepAlive(SSHClient client) {
        var keepAlive = client.getConnection().getKeepAlive();
        keepAlive.setKeepAliveInterval(KEEP_ALIVE_INTERVAL_SECONDS);
        if (keepAlive.getState() == Thread.State.NEW) {
            keepAlive.start();
        }
        log.info("SSH keepalive started after authentication: interval={}s", KEEP_ALIVE_INTERVAL_SECONDS);
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
