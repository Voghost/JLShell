package com.jlshell.data.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;

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
}
