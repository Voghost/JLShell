package com.jlshell.app;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.jlshell.core.config.CoreExecutorProperties;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.service.SessionRegistry;
import com.jlshell.core.service.impl.DefaultSessionManager;
import com.jlshell.core.service.impl.InMemorySessionRegistry;
import com.jlshell.core.service.impl.PersistentFontProfileService;
import com.jlshell.core.support.NamedThreadFactory;
import com.jlshell.data.config.CredentialEncryptionProperties;
import com.jlshell.data.config.DatabaseFactory;
import com.jlshell.data.crypto.AesGcmCredentialCipher;
import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.data.crypto.FileSystemMasterKeyProvider;
import com.jlshell.data.service.JdbiAppSettingsService;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.support.SshjSftpService;
import com.jlshell.ssh.support.EphemeralTrustHostKeyVerifier;
import com.jlshell.ssh.support.SshjConnectionManager;
import com.jlshell.terminal.support.JediTermTerminalViewFactory;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.LocalShellLauncher;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.view.MainWindow;
import com.jlshell.ui.viewmodel.MainViewModel;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手动依赖注入容器，替代 Spring IoC。
 * 按依赖顺序构造所有对象：基础设施 → 服务 → UI。
 */
public class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final MainWindow mainWindow;

    public AppContext() {
        String userHome = System.getProperty("user.home");
        String jdbcUrl = "jdbc:sqlite:" + userHome + "/.jlshell/jlshell.db";

        // 1. Database
        dataSource = DatabaseFactory.createDataSource(jdbcUrl);
        Jdbi jdbi = DatabaseFactory.createJdbi(dataSource);
        DatabaseFactory.initSchema(jdbi);
        log.info("Database initialised at {}", jdbcUrl);

        // 2. Credential cipher
        CredentialEncryptionProperties encryptionProperties = new CredentialEncryptionProperties();
        FileSystemMasterKeyProvider keyProvider = new FileSystemMasterKeyProvider(encryptionProperties);
        CredentialCipher credentialCipher = new AesGcmCredentialCipher(keyProvider.loadOrCreate());

        // 3. Core services
        AppSettingsService appSettingsService = new JdbiAppSettingsService(jdbi);

        CoreExecutorProperties executorProperties = new CoreExecutorProperties();
        executor = new ThreadPoolExecutor(
                executorProperties.getCorePoolSize(),
                executorProperties.getMaxPoolSize(),
                executorProperties.getKeepAlive().toMillis(), TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(executorProperties.getQueueCapacity()),
                new NamedThreadFactory("jlshell-ssh"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        SessionRegistry sessionRegistry = new InMemorySessionRegistry();
        FontProfileService fontProfileService = new PersistentFontProfileService(appSettingsService);

        // 4. SSH / SFTP
        SshjConnectionManager connectionManager = new SshjConnectionManager(
                executor, new EphemeralTrustHostKeyVerifier());
        SessionManager sessionManager = new DefaultSessionManager(connectionManager, sessionRegistry);
        SftpService sftpService = new SshjSftpService(executor);

        // 5. Plugins
        PluginManager pluginManager = new PluginManager();
        pluginManager.loadPlugins();

        // 6. UI services
        I18nService i18nService = new I18nService(Locale.getDefault());
        ThemeService themeService = new ThemeService();
        MainViewModel viewModel = new MainViewModel();

        JediTermTerminalViewFactory terminalViewFactory = new JediTermTerminalViewFactory(
                fontProfileService, executor, i18nService::get);

        ConnectionProfileService connectionProfileService = new ConnectionProfileService(jdbi, credentialCipher);
        LocalShellLauncher localShellLauncher = new LocalShellLauncher(fontProfileService, executor, i18nService);

        // 7. Main window
        mainWindow = new MainWindow(
                viewModel,
                connectionProfileService,
                sessionManager,
                terminalViewFactory,
                fontProfileService,
                appSettingsService,
                sftpService,
                themeService,
                i18nService,
                localShellLauncher,
                executor,
                5,
                pluginManager
        );

        log.info("AppContext initialised");
    }

    public MainWindow getMainWindow() {
        return mainWindow;
    }

    @Override
    public void close() {
        log.info("AppContext shutting down");
        executor.shutdownNow();
        dataSource.close();
    }
}
