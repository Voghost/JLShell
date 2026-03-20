-- JLShell SQLite schema
-- 时间戳存储为 INTEGER（Unix 毫秒），与 Hibernate 历史数据兼容
-- 布尔值存储为 INTEGER (0/1)

CREATE TABLE IF NOT EXISTS projects (
    id          TEXT    PRIMARY KEY NOT NULL,
    name        TEXT    NOT NULL,
    description TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS credentials (
    id                  TEXT PRIMARY KEY NOT NULL,
    authentication_type TEXT NOT NULL,
    encrypted_password  TEXT,
    encrypted_passphrase TEXT,
    private_key_path    TEXT,
    created_at          INTEGER NOT NULL,
    updated_at          INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS connection_folders (
    id         TEXT    PRIMARY KEY NOT NULL,
    name       TEXT    NOT NULL,
    parent_id  TEXT    REFERENCES connection_folders(id),
    project_id TEXT    REFERENCES projects(id),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS connections (
    id                       TEXT    PRIMARY KEY NOT NULL,
    display_name             TEXT    NOT NULL,
    host                     TEXT    NOT NULL DEFAULT '',
    port                     INTEGER NOT NULL DEFAULT 22,
    username                 TEXT    NOT NULL DEFAULT '',
    authentication_type      TEXT    NOT NULL DEFAULT 'PASSWORD',
    host_key_verification_mode TEXT  NOT NULL DEFAULT 'STRICT',
    description              TEXT,
    default_remote_path      TEXT,
    favorite                 INTEGER NOT NULL DEFAULT 0,
    connection_type          TEXT    DEFAULT 'SSH',
    project_id               TEXT    REFERENCES projects(id),
    folder_id                TEXT    REFERENCES connection_folders(id),
    credential_id            TEXT    UNIQUE REFERENCES credentials(id),
    created_at               INTEGER NOT NULL,
    updated_at               INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS session_history (
    id                 TEXT    PRIMARY KEY NOT NULL,
    connection_id      TEXT    NOT NULL REFERENCES connections(id),
    session_identifier TEXT    NOT NULL,
    state              TEXT    NOT NULL,
    opened_at          INTEGER NOT NULL,
    closed_at          INTEGER,
    remote_address     TEXT,
    exit_code          INTEGER,
    failure_reason     TEXT,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS app_settings (
    id            TEXT PRIMARY KEY NOT NULL,
    setting_key   TEXT NOT NULL UNIQUE,
    setting_value TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);
