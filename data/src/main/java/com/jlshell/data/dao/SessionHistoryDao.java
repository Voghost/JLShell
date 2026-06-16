package com.jlshell.data.dao;

import java.util.Optional;

import com.jlshell.data.entity.RecentSessionEntry;
import com.jlshell.data.entity.SessionHistoryEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 会话历史 JDBI DAO。
 */
public interface SessionHistoryDao {

    @SqlQuery("SELECT * FROM session_history WHERE id = :id")
    @RegisterBeanMapper(SessionHistoryEntity.class)
    Optional<SessionHistoryEntity> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO session_history (id, connection_id, session_identifier, state, opened_at, " +
            "closed_at, remote_address, exit_code, failure_reason, created_at, updated_at) " +
            "VALUES (:id, :connectionId, :sessionIdentifier, :state, :openedAt, " +
            ":closedAt, :remoteAddress, :exitCode, :failureReason, :createdAt, :updatedAt)")
    void insert(@BindBean SessionHistoryEntity entity);

    @SqlUpdate("UPDATE session_history SET state=:state, closed_at=:closedAt, exit_code=:exitCode, " +
            "failure_reason=:failureReason, updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean SessionHistoryEntity entity);

    @SqlQuery("SELECT sh.id, sh.connection_id, sh.opened_at, sh.closed_at, sh.state, " +
            "c.display_name, c.host, c.port, c.username, c.connection_type, c.favorite " +
            "FROM session_history sh " +
            "JOIN connections c ON c.id = sh.connection_id " +
            "WHERE sh.opened_at = (" +
            "  SELECT MAX(sh2.opened_at) FROM session_history sh2 WHERE sh2.connection_id = sh.connection_id" +
            ") " +
            "ORDER BY sh.opened_at DESC LIMIT :limit")
    @RegisterBeanMapper(RecentSessionEntry.class)
    java.util.List<RecentSessionEntry> findRecentWithConnectionInfo(@Bind("limit") int limit);
}
