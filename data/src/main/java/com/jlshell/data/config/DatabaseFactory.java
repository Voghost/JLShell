package com.jlshell.data.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.enums.EnumStrategy;
import org.jdbi.v3.core.enums.Enums;
import org.jdbi.v3.core.mapper.reflect.ReflectionMappers;
import org.jdbi.v3.core.mapper.reflect.SnakeCaseColumnNameMatcher;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * 数据库工厂：创建 HikariCP DataSource 和配置好的 Jdbi 实例。
 */
public class DatabaseFactory {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFactory.class);

    private DatabaseFactory() {}

    public static HikariDataSource createDataSource(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1); // SQLite 仅支持单写连接
        config.setMinimumIdle(1);
        config.setConnectionTestQuery("SELECT 1");
        // SQLite WAL 模式提升并发读性能
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;");
        return new HikariDataSource(config);
    }

    public static Jdbi createJdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(dataSource);

        // 安装 SQL Object 插件
        jdbi.installPlugin(new SqlObjectPlugin());

        // 枚举按名称映射（与 Hibernate 历史数据兼容）
        jdbi.getConfig(Enums.class).setEnumStrategy(EnumStrategy.BY_NAME);

        // 列名 snake_case ↔ camelCase 自动转换
        jdbi.getConfig(ReflectionMappers.class).setColumnNameMatchers(
                java.util.List.of(new SnakeCaseColumnNameMatcher()));

        // Instant ↔ 毫秒时间戳（与 Hibernate 生成的数据兼容）
        jdbi.registerColumnMapper(Instant.class, (rs, col, ctx) -> {
            long ms = rs.getLong(col);
            return rs.wasNull() ? null : Instant.ofEpochMilli(ms);
        });
        jdbi.registerArgument(new AbstractArgumentFactory<Instant>(Types.BIGINT) {
            @Override
            protected Argument build(Instant value, ConfigRegistry config) {
                return (pos, stmt, ctx) -> stmt.setLong(pos, value.toEpochMilli());
            }
        });

        return jdbi;
    }

    /** 执行 schema.sql，创建不存在的表（幂等）。 */
    public static void initSchema(Jdbi jdbi) {
        try (InputStream is = DatabaseFactory.class.getResourceAsStream("/schema.sql")) {
            if (is == null) {
                log.warn("schema.sql not found on classpath, skipping schema init");
                return;
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            jdbi.useHandle(handle -> {
                for (String statement : sql.split(";")) {
                    String trimmed = statement.strip();
                    if (!trimmed.isEmpty()) {
                        handle.execute(trimmed);
                    }
                }
            });
            log.info("Database schema initialized");
        } catch (Exception e) {
            log.error("Failed to initialize database schema", e);
            throw new RuntimeException("Database schema initialization failed", e);
        }
    }

    /**
     * 一次性迁移：将 credentials 表数据迁移到 credential_vault，
     * 并为 connections 表添加 vault_entry_id 列、移除 credential_id 的 UNIQUE 约束。
     * 幂等：如果 vault_entry_id 列已存在则跳过。
     */
    public static void migrateVault(Jdbi jdbi) {
        jdbi.useHandle(h -> {
            // 检查 vault_entry_id 列是否已存在
            boolean vaultColumnExists = h.createQuery("PRAGMA table_info(connections)")
                    .mapToMap()
                    .list()
                    .stream()
                    .anyMatch(row -> "vault_entry_id".equals(row.get("name")));

            if (vaultColumnExists) {
                log.debug("Vault migration already applied, skipping");
                return;
            }

            log.info("Starting vault migration...");

            // 1. 迁移 credentials → credential_vault
            List<Map<String, Object>> creds = h.createQuery(
                    "SELECT c.id AS cred_id, c.authentication_type, c.encrypted_password, " +
                    "c.encrypted_passphrase, c.private_key_path, " +
                    "conn.id AS conn_id, conn.display_name, conn.project_id " +
                    "FROM credentials c " +
                    "JOIN connections conn ON conn.credential_id = c.id")
                    .mapToMap().list();

            for (Map<String, Object> row : creds) {
                String newId = UUID.randomUUID().toString();
                String name = "Migrated: " + row.get("display_name");
                long now = Instant.now().toEpochMilli();
                h.execute("INSERT INTO credential_vault (id, name, authentication_type, " +
                        "encrypted_password, encrypted_passphrase, private_key_path, " +
                        "project_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        newId, name, row.get("authentication_type"),
                        row.get("encrypted_password"), row.get("encrypted_passphrase"),
                        row.get("private_key_path"), row.get("project_id"), now, now);

                // 暂时更新 connections 行（vault_entry_id 列还不存在，稍后通过表重建添加）
                // 先记录映射关系，等表重建后直接写入
            }

            // 2. 重建 connections 表：移除 credential_id UNIQUE 约束，添加 vault_entry_id 列
            h.execute("PRAGMA foreign_keys=OFF");

            h.execute("CREATE TABLE connections_new (" +
                    "id                       TEXT    PRIMARY KEY NOT NULL," +
                    "display_name             TEXT    NOT NULL," +
                    "host                     TEXT    NOT NULL DEFAULT ''," +
                    "port                     INTEGER NOT NULL DEFAULT 22," +
                    "username                 TEXT    NOT NULL DEFAULT ''," +
                    "authentication_type      TEXT    NOT NULL DEFAULT 'PASSWORD'," +
                    "host_key_verification_mode TEXT  NOT NULL DEFAULT 'STRICT'," +
                    "description              TEXT," +
                    "default_remote_path      TEXT," +
                    "favorite                 INTEGER NOT NULL DEFAULT 0," +
                    "connection_type          TEXT    DEFAULT 'SSH'," +
                    "project_id               TEXT    REFERENCES projects(id)," +
                    "folder_id                TEXT    REFERENCES connection_folders(id)," +
                    "credential_id            TEXT    REFERENCES credentials(id)," +
                    "vault_entry_id           TEXT    REFERENCES credential_vault(id)," +
                    "created_at               INTEGER NOT NULL," +
                    "updated_at               INTEGER NOT NULL)");

            // 3. 复制数据：先不带 vault_entry_id（该列在旧表中不存在）
            h.execute("INSERT INTO connections_new (id, display_name, host, port, username, " +
                    "authentication_type, host_key_verification_mode, description, default_remote_path, " +
                    "favorite, connection_type, project_id, folder_id, credential_id, " +
                    "created_at, updated_at) " +
                    "SELECT id, display_name, host, port, username, " +
                    "authentication_type, host_key_verification_mode, description, default_remote_path, " +
                    "favorite, connection_type, project_id, folder_id, credential_id, " +
                    "created_at, updated_at FROM connections");

            // 4. 将迁移映射的 vault_entry_id 写入新表
            for (Map<String, Object> row : creds) {
                String connId = (String) row.get("conn_id");
                // 查找刚插入的 vault entry
                String vaultName = "Migrated: " + row.get("display_name");
                String projectId = row.get("project_id") != null ? (String) row.get("project_id") : null;
                // 使用 display_name + project_id 匹配（刚插入的唯一标识）
                List<String> vaultIds = h.createQuery(
                        "SELECT id FROM credential_vault WHERE name = ? LIMIT 1")
                        .bind(0, vaultName)
                        .mapTo(String.class)
                        .list();
                if (!vaultIds.isEmpty()) {
                    h.execute("UPDATE connections_new SET vault_entry_id = ? WHERE id = ?",
                            vaultIds.get(0), connId);
                }
            }

            // 5. 替换旧表
            h.execute("DROP TABLE connections");
            h.execute("ALTER TABLE connections_new RENAME TO connections");

            h.execute("PRAGMA foreign_keys=ON");

            log.info("Vault migration completed: {} credential entries migrated", creds.size());
        });
    }

    /**
     * 增量迁移：为 credential_vault 表添加 encryption_mode 列（如果缺失）。
     */
    public static void migrateVaultEncryptionMode(Jdbi jdbi) {
        jdbi.useHandle(h -> {
            boolean hasEncryptionMode = h.createQuery("PRAGMA table_info(credential_vault)")
                    .mapToMap()
                    .list()
                    .stream()
                    .anyMatch(row -> "encryption_mode".equals(row.get("name")));

            if (hasEncryptionMode) return;

            h.execute("ALTER TABLE credential_vault ADD COLUMN encryption_mode TEXT NOT NULL DEFAULT 'SYSTEM'");
            log.info("Added encryption_mode column to credential_vault");
        });
    }
}
