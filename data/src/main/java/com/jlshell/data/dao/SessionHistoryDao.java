package com.jlshell.data.dao;

import java.util.Optional;

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
}
