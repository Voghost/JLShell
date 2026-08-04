package com.jlshell.app;

import java.util.Locale;
import java.util.List;
import java.util.ServiceLoader;
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
import com.jlshell.core.shortcut.ShortcutRegistry;
import com.jlshell.core.support.NamedThreadFactory;
import com.jlshell.app.api.CoreProgramSessionService;
import com.jlshell.app.api.HostAccountSessionService;
import com.jlshell.data.config.CredentialEncryptionProperties;
import com.jlshell.data.config.DatabaseFactory;
import com.jlshell.data.crypto.AesGcmCredentialCipher;
import com.jlshell.data.crypto.CredentialCipher;
import com.jlshell.data.crypto.FileSystemMasterKeyProvider;
import com.jlshell.data.service.JdbiAppSettingsService;
import com.jlshell.data.service.JdbiCustomColorSchemeStore;
import com.jlshell.data.service.EncryptedAppSettingsService;
import com.jlshell.data.JdbiPluginStorage;
import com.jlshell.data.JdbiSecurePluginStorage;
import com.jlshell.plugin.loader.CapabilityBusImpl;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.program.api.DefaultProgramApiRegistry;
import com.jlshell.program.api.ProgramApiContext;
import com.jlshell.program.api.ProgramApiProvider;
import com.jlshell.program.api.ProgramApiRegistry;
import com.jlshell.program.api.ProgramSessionService;
import com.jlshell.program.api.AccountSessionService;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.support.SshjSftpService;
import com.jlshell.ssh.support.EphemeralTrustHostKeyVerifier;
import com.jlshell.ssh.support.SshjConnectionManager;
import com.jlshell.terminal.service.BuiltInColorSchemeLoader;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.JediTermTerminalViewFactory;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.HostKeyConfirmationService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.LocalShellLauncher;
import com.jlshell.ui.service.MemoryReclaimService;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.service.update.UpdateService;
import com.jlshell.ui.service.VaultKeyService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.support.BundledFontLoader;
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
    private final com.jlshell.api.server.ApiServer apiServer;
    private final ProgramPluginManager programPluginManager;
    private final List<ProgramApiProvider> programApiProviders;
    private final MemoryReclaimService memoryReclaimService;
    private final AccountService accountService;

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

        // 4. I18n & Theme (needed before SSH for host key confirmation dialogs)
        // 首次启动：根据系统语言环境设置语言
        String savedLang = appSettingsService.get("ui.language", null);
        Locale initialLocale;
        if (savedLang != null) {
            initialLocale = savedLang.contains("_")
                    ? new Locale(savedLang.split("_")[0], savedLang.split("_")[1])
                    : new Locale(savedLang);
        } else {
            initialLocale = Locale.getDefault();
            log.info("No saved language preference, using system locale: {}", initialLocale);
        }
        log.info("Initial locale: {} (savedLang={})", initialLocale, savedLang);
        I18nService i18nService = new I18nService(initialLocale);

        // Color scheme registry (built-in + custom persistence)
        BuiltInColorSchemeLoader builtInLoader = new BuiltInColorSchemeLoader();
        JdbiCustomColorSchemeStore customStore = new JdbiCustomColorSchemeStore(jdbi);
        ColorSchemeRegistry colorSchemeRegistry = new ColorSchemeRegistry(builtInLoader, customStore);

        ThemeService themeService = new ThemeService(appSettingsService, colorSchemeRegistry, executor);

        // 5. SSH / SFTP
        SshjConnectionManager connectionManager = new SshjConnectionManager(
                executor, new EphemeralTrustHostKeyVerifier(), new HostKeyConfirmationService(i18nService, themeService));
        SessionManager sessionManager = new DefaultSessionManager(connectionManager, sessionRegistry);
        SftpService sftpService = new SshjSftpService(executor);

        // 6. Plugins — 延迟到首次 getAvailablePlugins()/activatePlugin() 才扫描 JAR
        String hostVersion = com.jlshell.ui.dialog.PreferencesDialog.getVersion();
        com.jlshell.plugin.loader.PluginEnablementService pluginEnablementService =
                new com.jlshell.plugin.loader.PluginEnablementService(appSettingsService);
        PluginManager pluginManager = new PluginManager(
                userHome + "/.jlshell/plugins", hostVersion, pluginEnablementService);

        // 6b. RPC 内核 + 外部 API
        CapabilityBusImpl capabilityBus = new CapabilityBusImpl(pluginManager);
        java.util.function.Function<String, com.jlshell.plugin.api.storage.PluginStorage> storageFactory =
                pluginId -> new JdbiPluginStorage(jdbi, pluginId);
        java.util.function.Function<String, com.jlshell.plugin.api.storage.SecureStorage> secureStorageFactory =
                pluginId -> new JdbiSecurePluginStorage(jdbi, pluginId, credentialCipher);
        pluginManager.setSecureStorageFactory(secureStorageFactory);
        boolean apiEnabled = "true".equalsIgnoreCase(appSettingsService.get("api.enabled", "false"));
        int apiPort = parsePortOrDefault(appSettingsService.get("api.port", "0"), 0);
        String apiToken;
        try {
            apiToken = apiEnabled ? com.jlshell.api.server.ApiTokenStore.loadOrCreate() : "";
        } catch (Exception e) {
            log.warn("Failed to init API token (non-fatal): {}", e.getMessage());
            apiToken = "";
        }
        com.jlshell.api.server.ApiServerConfig apiCfg =
                new com.jlshell.api.server.ApiServerConfig(apiPort, apiToken, apiEnabled);
        final String externalApiToken = apiToken;

        // 6.5. Vault service + migration
        VaultKeyService vaultKeyService = new VaultKeyService(appSettingsService);
        VaultService vaultService = new VaultService(jdbi, credentialCipher, vaultKeyService);
        DatabaseFactory.migrateVault(jdbi);
        DatabaseFactory.migrateVaultEncryptionMode(jdbi);

        // 7. UI services
        MainViewModel viewModel = new MainViewModel();

        ShortcutRegistry shortcutRegistry = new ShortcutRegistry(appSettingsService);

        JediTermTerminalViewFactory terminalViewFactory = new JediTermTerminalViewFactory(
                fontProfileService, executor, i18nService::get, BundledFontLoader::ensureAwtRegistered,
                shortcutRegistry);

        ConnectionProfileService connectionProfileService = new ConnectionProfileService(
                jdbi, credentialCipher, appSettingsService, vaultService);
        LocalShellLauncher localShellLauncher = new LocalShellLauncher(
                fontProfileService, executor, i18nService, BundledFontLoader::ensureAwtRegistered,
                shortcutRegistry);
        memoryReclaimService = new MemoryReclaimService();
        UpdateService updateService = new UpdateService(appSettingsService, executor);
        this.accountService = new AccountService(appSettingsService,
                new EncryptedAppSettingsService(appSettingsService, credentialCipher), executor);
        AccountSessionService accountSessionService = new HostAccountSessionService(accountService);

        // 7b. Program API SPI + API server
        ProgramApiRegistry programApiRegistry = new DefaultProgramApiRegistry();
        ProgramSessionService programSessionService = new CoreProgramSessionService(
                connectionProfileService::toConnectionRequest, sessionManager, executor);
        ProgramApiContext programApiContext = new ProgramApiContext() {
            @Override public ProgramApiRegistry registry() { return programApiRegistry; }
            @Override public ProgramSessionService sessions() { return programSessionService; }
            @Override public String apiToken() { return externalApiToken; }
            @Override public java.util.concurrent.Executor executor() { return executor; }
            @Override public AccountSessionService accountSession() { return accountSessionService; }
        };
        List<ProgramApiProvider> programApiProviders = ServiceLoader
                .load(ProgramApiProvider.class, AppContext.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (programApiProviders.isEmpty()) {
            log.warn("No ProgramApiProvider was discovered; external host methods are unavailable");
        }
        programApiProviders.forEach(provider -> provider.activate(programApiContext));
        ProgramPluginManager programPluginManager = new ProgramPluginManager(
                userHome + "/.jlshell/program-plugins",
                hostVersion,
                pluginManager,
                pluginManager.globalRegistry(),
                capabilityBus,
                storageFactory,
                secureStorageFactory,
                (key, fallback) -> {
                    String value = i18nService.get(key);
                    return value == null || value.isBlank() || value.equals(key) ? fallback : value;
                },
                programApiContext,
                pluginEnablementService,
                com.jlshell.program.plugin.loader.ProjectIntegrationRegistry.shared(),
                accountSessionService
        );
        programPluginManager.setThemeName(themeService.currentThemeProperty().get().name().toLowerCase());
        programPluginManager.setLocale(initialLocale);
        programPluginManager.ensureLoaded();
        com.jlshell.plugin.loader.HeadlessSessionPluginActivator sessionPluginActivator =
                new com.jlshell.plugin.loader.HeadlessSessionPluginActivator(
                        pluginManager, sessionManager, sftpService, capabilityBus, storageFactory);
        com.jlshell.api.server.ApiServer apiServer =
                new com.jlshell.api.server.ApiServer(apiCfg, capabilityBus, programApiRegistry, sessionPluginActivator);
        if (apiEnabled) {
            try {
                apiServer.start();
                log.info("External API on 127.0.0.1:{} (token at ~/.jlshell/api.token)", apiServer.port());
            } catch (java.io.IOException e) {
                log.warn("API server failed to start (non-fatal): {}", e.getMessage());
            }
        }

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
                vaultService,
                5,
                pluginManager,
                programPluginManager,
                apiServer,
                capabilityBus,
                storageFactory,
                memoryReclaimService,
                updateService,
                accountService,
                shortcutRegistry
        );

        this.apiServer = apiServer;
        this.programPluginManager = programPluginManager;
        this.programApiProviders = programApiProviders;

        log.info("AppContext initialised");
    }

    public MainWindow getMainWindow() {
        return mainWindow;
    }

    @Override
    public void close() {
        log.info("AppContext shutting down");
        if (apiServer != null) apiServer.stop();
        programApiProviders.forEach(ProgramApiProvider::deactivate);
        if (programPluginManager != null) programPluginManager.deactivateAll();
        if (accountService != null) accountService.shutdown();
        if (memoryReclaimService != null) memoryReclaimService.close();
        if (mainWindow != null) mainWindow.flushPendingUiState();
        executor.shutdownNow();
        dataSource.close();
    }

    private static int parsePortOrDefault(String s, int def) {
        try { int p = Integer.parseInt(s); return (p >= 0 && p <= 65535) ? p : def; }
        catch (NumberFormatException e) { return def; }
    }
}
